/* Copyright 2017 Alfa Financial Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.alfasoftware.morf.integration.testdatabaseupgradeintegration.upgrade.v1_0_0;

import org.alfasoftware.morf.metadata.DataType;
import org.alfasoftware.morf.upgrade.DataEditor;
import org.alfasoftware.morf.upgrade.SchemaEditor;
import org.alfasoftware.morf.upgrade.Sequence;
import org.alfasoftware.morf.upgrade.UUID;

import static org.alfasoftware.morf.metadata.SchemaUtils.column;

@Sequence(1)
@UUID("94c5e6bc-e65b-477c-bee2-71f9e3548105")
public class ReduceStringColumnWidth extends AbstractTestUpgradeStep {
  @Override
  public void execute(SchemaEditor schema, DataEditor data) {
    // reduce the width from 10 to 8, which the data accomodates
    schema.changeColumn("BasicTable", column("nullableStringCol", DataType.STRING, 10).nullable(), column("nullableStringCol", DataType.STRING, 8).nullable());
  }
}