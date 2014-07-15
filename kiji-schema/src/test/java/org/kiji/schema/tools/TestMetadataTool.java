/**
 * (c) Copyright 2013 WibiData, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Lists;
import org.junit.Test;

import org.kiji.common.tools.BaseTool;
import org.kiji.schema.Kiji;
import org.kiji.schema.layout.KijiTableLayouts;

public class TestMetadataTool extends KijiToolTest {
  @Test
  public void testInTempFolder() throws Exception {
    final File backupFile = new File(getLocalTempDir(), "tempMetadataBackup");

    final Kiji kijiBackup = createTestKiji();
    final Kiji kijiRestored = createTestKiji();
    assertFalse(kijiBackup.getURI().equals(kijiRestored.getURI()));

    // Make this a non-trivial instance by creating a couple of tables.
    kijiBackup.createTable(KijiTableLayouts.getLayout(KijiTableLayouts.FOO_TEST));
    kijiBackup.createTable(KijiTableLayouts.getLayout(KijiTableLayouts.FULL_FEATURED));

    // Backup metadata for kijiBackup.
    final List<String> args1 = Lists.newArrayList(
        "--kiji=" + kijiBackup.getURI().toOrderedString(),
        "--backup=" + backupFile.getPath()
    );
    final MetadataTool tool1 = new MetadataTool();
    assertEquals(BaseTool.SUCCESS, tool1.toolMain(args1, getConf()));

    // Check that kijiBackup and kijiRestored do not intersect.
    assertFalse(kijiRestored.getTableNames().containsAll(kijiBackup.getTableNames()));

    // Restore metadata from kijiBackup to kijiRestored.
    final ArrayList<String> args2 = Lists.newArrayList(
        "--kiji=" + kijiRestored.getURI().toOrderedString(),
        "--restore=" + backupFile.getPath(),
        "--interactive=false"
    );
    final MetadataTool tool2 = new MetadataTool();
    assertEquals(BaseTool.SUCCESS, tool2.toolMain(args2, getConf()));
    assertTrue(kijiRestored.getTableNames().containsAll(kijiBackup.getTableNames()));
  }
}
