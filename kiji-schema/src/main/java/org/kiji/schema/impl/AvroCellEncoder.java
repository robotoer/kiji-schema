/**
 * (c) Copyright 2012 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericContainer;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.DecodedCell;
import org.kiji.schema.KijiCellEncoder;
import org.kiji.schema.KijiEncodingException;
import org.kiji.schema.KijiIOException;
import org.kiji.schema.avro.AvroValidationPolicy;
import org.kiji.schema.avro.CellSchema;
import org.kiji.schema.avro.SchemaStorage;
import org.kiji.schema.layout.CellSpec;
import org.kiji.schema.util.ByteStreamArray;
import org.kiji.schema.util.BytesKey;

/**
 * Serializes Avro cells to bytes for persistence in HBase.
 *
 * <p>
 *   An Avro cell encoder is specific to one column in a KijiTable.
 *   Depending on the column specification, Avro cells embed the writer schema or not.
 *   When embedded, the Avro schema ID/hash is prepended to the encoded value.
 * <p>
 */
@ApiAudience.Private
public final class AvroCellEncoder implements KijiCellEncoder {
  /** Name of the system property to control schema validation. */
  public static final String SCHEMA_VALIDATION =
      "org.kiji.schema.impl.AvroCellEncoder.SCHEMA_VALIDATION";

  /** Schema validation policies, until AVRO-1315 is available. */
  private enum SchemaValidationPolicy {
    DISABLED,
    SCHEMA_1_0,
    ENABLED
  }

  /** Mapping from class names of Avro primitives to their corresponding Avro schemas. */
  public static final Map<String, Schema> PRIMITIVE_SCHEMAS;
  static {
    final Schema booleanSchema = Schema.create(Schema.Type.BOOLEAN);
    final Schema intSchema = Schema.create(Schema.Type.INT);
    final Schema longSchema = Schema.create(Schema.Type.LONG);
    final Schema floatSchema = Schema.create(Schema.Type.FLOAT);
    final Schema doubleSchema = Schema.create(Schema.Type.DOUBLE);
    final Schema stringSchema = Schema.create(Schema.Type.STRING);

    // Initialize primitive schema mapping.
    PRIMITIVE_SCHEMAS = ImmutableMap
        .<String, Schema>builder()
        .put("boolean", booleanSchema)
        .put("java.lang.Boolean", booleanSchema)
        .put("int", intSchema)
        .put("java.lang.Integer", intSchema)
        .put("long", longSchema)
        .put("java.lang.Long", longSchema)
        .put("float", floatSchema)
        .put("java.lang.Float", floatSchema)
        .put("double", doubleSchema)
        .put("java.lang.Double", doubleSchema)
        .put("java.lang.String", stringSchema)
        .put("org.apache.avro.util.Utf8", stringSchema)
        .put("java.nio.ByteBuffer", Schema.create(Schema.Type.BYTES))
        .build();
  }

  /**
   * Reports the Avro schema validation policy.
   *
   * @param cellSchema to get the Avro schema validation policy from.
   * @return the schema validation policy.
   */
  private static AvroValidationPolicy getAvroValidationPolicy(final CellSchema cellSchema) {
    final String validationPolicy = System.getProperty(SCHEMA_VALIDATION, "ENABLED");
    try {
      switch (SchemaValidationPolicy.valueOf(validationPolicy)) {
        case DISABLED: {
          return AvroValidationPolicy.NONE;
        }
        case SCHEMA_1_0: {
          return AvroValidationPolicy.SCHEMA_1_0;
        }
        default: {
          return cellSchema.getAvroValidationPolicy();
        }
      }
    } catch (IllegalArgumentException iae) {
      throw new KijiEncodingException(
          String.format("Unrecognized validation policy: %s", validationPolicy), iae);
    }
  }

  /** Specification of the column to encode. */
  private final CellSpec mCellSpec;

  /** Schema encoder. */
  private final SchemaEncoder mSchemaEncoder;

  /**
   * Cache of Avro DatumWriter.
   *
   * <p>
   *   Avro datum writers aren't thread-safe, but if we ensure the schema of a datum writer is not
   *   modified, the datum writer becomes thread-safe.
   * </p>
   *
   * <p>
   *   This cache is not globally shared at present.
   *   To share this map globally (ie. static) requires using a WeakIdentityHashMap:
   *   a weak map is required to garbage collect unused schemas;
   *   an identity map is also required as Schema.hashCode/equals are imperfect.
   * </p>
   */
  private final Map<Schema, DatumWriter<Object>> mCachedDatumWriters = Maps.newHashMap();

  /**
   * A byte stream for when encoding to a byte array.
   *
   * Since we use the same instance for all encodings, this makes the encoder thread-unsafe.
   */
  private final ByteArrayOutputStream mByteArrayOutputStream = new ByteArrayOutputStream();

  /** An encoder that writes to the above byte stream. */
  private final Encoder mByteArrayEncoder =
      EncoderFactory.get().directBinaryEncoder(mByteArrayOutputStream, null);

  /**
   * Configured reader schema for the column to encode.
   *
   * This may currently be null if we only know the fully-qualified name of the record.
   * Eventually, this will always be populated so we can validate records being written against
   * the present reader schema.
   */
  private final Schema mReaderSchema;

  /**
   * Writer schemas registered for the column that this cell encoder will encode cells for.
   *
   * Note: This will be null if schema validation is disabled.
   */
  private Set<Schema> mRegisteredWriters;

  // -----------------------------------------------------------------------------------------------

  /**
   * Encodes the writer schema.
   */
  private interface SchemaEncoder {
    /**
     * Encodes the writer schema in the cell.
     *
     * @param writerSchema Avro schema of the data being encoded.
     * @throws IOException on I/O error.
     */
    void encode(Schema writerSchema) throws IOException;
  }

  // -----------------------------------------------------------------------------------------------

  /** Schema encoders that uses a hash of the schema. */
  private class SchemaHashEncoder implements SchemaEncoder {
    /** {@inheritDoc} */
    @Override
    public void encode(Schema writerSchema) throws IOException {
      final BytesKey schemaHash = mCellSpec.getSchemaTable().getOrCreateSchemaHash(writerSchema);
      mByteArrayEncoder.writeFixed(schemaHash.getBytes());
    }
  }

  // -----------------------------------------------------------------------------------------------

  /** Schema encoders that uses the UID of the schema. */
  private class SchemaIdEncoder implements SchemaEncoder {
    /** {@inheritDoc} */
    @Override
    public void encode(Schema writerSchema) throws IOException {
      final long schemaId = mCellSpec.getSchemaTable().getOrCreateSchemaId(writerSchema);
      mByteArrayEncoder.writeFixed(ByteStreamArray.longToVarInt64(schemaId));
    }
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Schema encoders for final columns.
   *
   * <p>
   *   Schema is not encoded as part of the HBase cell.
   *   However, the Avro schema of the cell value must exactly match the column reader schema.
   *   In other words, the writer schema must be the reader schema at all times.
   * </p>
   */
  private static class FinalSchemaEncoder implements SchemaEncoder {
    /** Creates an encoder for a schema of a final column. */
    public FinalSchemaEncoder() {
    }

    /** {@inheritDoc} */
    @Override
    public void encode(Schema writerSchema) throws IOException {
      // Nothing to encode, because the writer schema is already encoded in the column layout.
      // This means the writer schema must be exactly the declared reader schema.
    }
  }

  /**
   * Creates a schema encoder for the specified cell encoding.
   *
   * @param cellSpec Specification of the cell to encode.
   * @return a schema encoder for the specified cell encoding.
   * @throws IOException on I/O error.
   */
  private SchemaEncoder createSchemaEncoder(CellSpec cellSpec) throws IOException {
    switch (cellSpec.getCellSchema().getStorage()) {
    case HASH: return new SchemaHashEncoder();
    case UID: return new SchemaIdEncoder();
    case FINAL: return new FinalSchemaEncoder();
    default:
      throw new RuntimeException(
          "Unexpected cell format: " + cellSpec.getCellSchema().getStorage());
    }
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Creates a new <code>KijiCellEncoder</code> instance.
   *
   * @param cellSpec Specification of the cell to encode.
   * @throws IOException on I/O error.
   */
  public AvroCellEncoder(CellSpec cellSpec) throws IOException {
    mCellSpec = Preconditions.checkNotNull(cellSpec);
    Preconditions.checkArgument(cellSpec.isAvro());
    mReaderSchema = mCellSpec.getAvroSchema();
    mSchemaEncoder = createSchemaEncoder(mCellSpec);
    mRegisteredWriters = getRegisteredWriters(mCellSpec);
  }

  /** {@inheritDoc} */
  @Override
  public byte[] encode(DecodedCell<?> cell) throws IOException {
    return encode(cell.getData());
  }

  /** {@inheritDoc} */
  @Override
  public synchronized <T> byte[] encode(T cellValue) throws IOException {
    mByteArrayOutputStream.reset();

    // Get the writer schema for this cell.
    Schema writerSchema = getWriterSchema(cellValue);

    // Handle any system property overrides for validation.
    final AvroValidationPolicy validationPolicy =
        getAvroValidationPolicy(mCellSpec.getCellSchema());

    // Perform avro schema validation (if necessary).
    switch (validationPolicy) {
      case STRICT: {
        if (!mRegisteredWriters.contains(writerSchema)) {
          throw new KijiEncodingException(
              String.format("Error trying to use unregistered writer schema: %s",
                  writerSchema.toString(true)));
        }
        break;
      }
      case DEVELOPER: {
        throw new UnsupportedOperationException(
            "The \"DEVELOPER\" schema validation mode is not currently supported");
      }
      case NONE: {
        break;
      }
      case SCHEMA_1_0: {
        // Compute the writer schema using old semantics. This will only validate primitive schemas.
        writerSchema =
            (cellValue instanceof GenericContainer)
            ? ((GenericContainer) cellValue).getSchema()
            : mReaderSchema;
        break;
      }
      default: {
        throw new KijiEncodingException(
            String.format("Unrecognized schema validation policy: %s",
                validationPolicy.toString()));
      }
    }

    // Perform final column schema validation (if necessary).
    if (mCellSpec.getCellSchema().getStorage() == SchemaStorage.FINAL
        && !writerSchema.equals(mReaderSchema)) {
      throw new KijiEncodingException(
          String.format("Writer schema: %s does not match final column schema: \"%s\"",
              writerSchema.toString(true),
              mReaderSchema.toString(true)));
    }

    // Encode the Avro schema (if necessary):
    mSchemaEncoder.encode(writerSchema);

    // Encode the cell value:
    try {
      getDatumWriter(writerSchema).write(cellValue, mByteArrayEncoder);
    } catch (ClassCastException cce) {
      throw new KijiEncodingException(cce);
    } catch (AvroRuntimeException ure) {
      throw new KijiEncodingException(ure);
    }
    return mByteArrayOutputStream.toByteArray();
  }

  /**
   * Gets a datum writer for a schema and caches it.
   *
   * <p> Not thread-safe, calls to this method must be externally synchronized. </p>
   *
   * @param schema The writer schema.
   * @return A datum writer for the given schema.
   */
  private DatumWriter<Object> getDatumWriter(Schema schema) {
    final DatumWriter<Object> existing = mCachedDatumWriters.get(schema);
    if (null != existing) {
      return existing;
    }
    final DatumWriter<Object> newWriter = new SpecificDatumWriter<Object>(schema);
    mCachedDatumWriters.put(schema, newWriter);
    return newWriter;
  }

  /**
   * Gets the writer schema of a specified value.
   *
   * @param <T> is the java type of the specified value.
   * @param cellValue to get the Avro schema of.
   * @return an Avro schema representing the type of data specified.
   */
  private static <T> Schema getWriterSchema(T cellValue) {
    if (cellValue instanceof GenericContainer) {
      return ((GenericContainer) cellValue).getSchema();
    } else {
      final String className = cellValue.getClass().getCanonicalName();
      return PRIMITIVE_SCHEMAS.get(className);
    }
  }

  /**
   * Gets the registered writer schemas associated with the provided cell specification.
   *
   * @param spec containing registered schemas.
   * @return the set of writer schemas registered for the provided cell.
   * @throws IOException if there is an error looking up schemas.
   */
  private static Set<Schema> getRegisteredWriters(CellSpec spec) throws IOException {
    final List<Long> writerUIDs = spec.getCellSchema().getWriters();
    if (writerUIDs == null) {
      return null;
    }

    final Set<Schema> writers = Sets.newHashSet();
    for (Long uid : writerUIDs) {
      // Convert uid to schema.
      final Schema writerSchema = spec.getSchemaTable().getSchema(uid);
      if (writerSchema == null) {
        throw new IOException(
            String.format("Unable to fetch schema with UID: %d from schema table.", uid));
      }

      // Add it to a hashset.
      writers.add(writerSchema);
    }
    return writers;
  }
}
