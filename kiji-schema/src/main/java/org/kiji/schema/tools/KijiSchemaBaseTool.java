/**
 * (c) Copyright 2014 WibiData, Inc.
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

package org.kiji.schema.tools;

import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;

import org.kiji.schema.KijiNotInstalledException;

/**
 * The base tool for kiji-schema tools.
 */
public abstract class KijiSchemaBaseTool extends org.kiji.common.tools.BaseTool {
  /**
   * KijiSchema tools require HBase specific configuration entries.
   *
   * {@inheritDoc}
   */
  @Override
  public Configuration generateConfiguration() {
    return HBaseConfiguration.create();
  }

  /**
   * Catch {@link org.kiji.schema.KijiNotInstalledException}s and print a helpful message
   * telling the user how to install a kiji instance.
   *
   * {@inheritDoc}
   */
  @Override
  public int toolMain(
      final List<String> args,
      final Configuration configuration
  ) throws Exception {
    try {
      return super.toolMain(args, configuration);
    } catch (final KijiNotInstalledException knie) {
      getPrintStream().println(knie.getMessage());
      getPrintStream().println("Try: kiji install --kiji=" + knie.getURI());
      return FAILURE;
    }
  }
}
