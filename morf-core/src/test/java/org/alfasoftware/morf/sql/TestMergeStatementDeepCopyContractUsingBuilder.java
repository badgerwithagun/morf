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

package org.alfasoftware.morf.sql;

import static org.alfasoftware.morf.sql.MergeStatement.merge;
import static org.alfasoftware.morf.sql.SqlUtils.literal;

import java.util.Arrays;
import java.util.List;

import org.alfasoftware.morf.sql.element.TableReference;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Checks that {@link MergeStatement} satisfies equals, hashcode and deep copy contracts.
 *
 * @author Copyright (c) Alfa Financial Software 2017
 */
@RunWith(Parameterized.class)
public class TestMergeStatementDeepCopyContractUsingBuilder extends AbstractShallowAndDeepCopyableTest<MergeStatement> {

  private static final SelectStatement SELECT_1 = mockSelectStatement();
  private static final SelectStatement SELECT_2 = mockSelectStatement();
  private static final TableReference TABLE_1 = mockOf(TableReference.class);
  private static final TableReference TABLE_2 = mockOf(TableReference.class);


  @Parameters(name = "{0}")
  public static List<Object[]> data() {
    return Arrays.asList(
      testCaseWithBuilder("1", merge().into(TABLE_1).from(SELECT_1).tableUniqueKey(literal(1))),
      testCaseWithBuilder("2", merge().into(TABLE_1).from(SELECT_1).tableUniqueKey(literal(2))),
      testCaseWithBuilder("3", merge().into(TABLE_1).from(SELECT_1).tableUniqueKey(literal(1), literal(1))),
      testCaseWithBuilder("4", merge().into(TABLE_1).from(SELECT_2).tableUniqueKey(literal(1))),
      testCaseWithBuilder("5", merge().into(TABLE_2).from(SELECT_1).tableUniqueKey(literal(1)))
    );
  }
}