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

package org.alfasoftware.morf.jdbc.nuodb;

import static org.alfasoftware.morf.metadata.SchemaUtils.index;
import static org.alfasoftware.morf.metadata.SchemaUtils.namesOfColumns;
import static org.alfasoftware.morf.metadata.SchemaUtils.primaryKeysForTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.alfasoftware.morf.jdbc.DatabaseType;
import org.alfasoftware.morf.jdbc.SqlDialect;
import org.alfasoftware.morf.metadata.Column;
import org.alfasoftware.morf.metadata.DataType;
import org.alfasoftware.morf.metadata.Index;
import org.alfasoftware.morf.metadata.Table;
import org.alfasoftware.morf.metadata.View;
import org.alfasoftware.morf.sql.MergeStatement;
import org.alfasoftware.morf.sql.element.AliasedField;
import org.alfasoftware.morf.sql.element.ConcatenatedField;
import org.alfasoftware.morf.sql.element.FieldLiteral;
import org.alfasoftware.morf.sql.element.FieldReference;
import org.alfasoftware.morf.sql.element.Function;
import org.alfasoftware.morf.sql.element.MathsField;
import org.alfasoftware.morf.sql.element.NullValueHandling;
import org.alfasoftware.morf.sql.element.TableReference;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.LocalDate;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;

/**
 * Implements database specific statement generation for NuoDB.
 *
 * @author Copyright (c) Alfa Financial Software 2017
 */
class NuoDBDialect extends SqlDialect {

  /**
   * The prefix to add to all temporary tables.
   */
  static final String TEMPORARY_TABLE_PREFIX = "TEMP_";
  static final Set<String> TEMPORARY_TABLES = new HashSet<>();


  /**
   *
   */
  public NuoDBDialect(String schemaName) {
    super(schemaName);
  }


  @Override
  protected String schemaNamePrefix(TableReference tableRef) {
    if (StringUtils.isEmpty(tableRef.getSchemaName())) {
      if (TEMPORARY_TABLES.contains(tableRef.getName())) {
        return "";
      }
      return schemaNamePrefix();
    } else {
      return tableRef.getSchemaName().toUpperCase() + ".";
    }
  }


  @Override
  protected String schemaNamePrefix(Table table) {
    if (table.isTemporary()) {
      return "";
    }
    return schemaNamePrefix().toUpperCase();
  }


  /**
   * When deploying a table need to ensure that an index doesn't already exist when creating it.
   * @see org.alfasoftware.morf.jdbc.SqlDialect#tableDeploymentStatements(org.alfasoftware.morf.metadata.Table)
   */
  @Override
  public Collection<String> tableDeploymentStatements(Table table) {
    Builder<String> statements = ImmutableList.<String>builder();

    statements.addAll(internalTableDeploymentStatements(table));

    for (Index index : table.indexes()) {
      statements.add(optionalDropIndexStatement(table, index));
      statements.add(indexDeploymentStatement(table, index));
    }

    return statements.build();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#tableDeploymentStatements(org.alfasoftware.morf.metadata.Table)
   */
  @Override
  public Collection<String> internalTableDeploymentStatements(Table table) {
    List<String> statements = new ArrayList<>();

    // Create the table deployment statement
    StringBuilder createTableStatement = new StringBuilder();
    createTableStatement.append("CREATE ");

    if (table.isTemporary()) {
      createTableStatement.append("TEMPORARY ");
      TEMPORARY_TABLES.add(table.getName());
    }

    createTableStatement.append("TABLE ");
    createTableStatement.append(qualifiedTableName(table));
    createTableStatement.append(" (");

    List<String> primaryKeys = new ArrayList<>();
    boolean first = true;
    Column autoNumbered = null;
    for (Column column : table.columns()) {
      if (!first) {
        createTableStatement.append(", ");
      }
      createTableStatement.append(column.getName() + " ");
      createTableStatement.append(sqlRepresentationOfColumnType(column));
      if (column.isAutoNumbered()) {
        autoNumbered = column;
        int autoNumberStart = autoNumbered.getAutoNumberStart() == -1 ? 1 : autoNumbered.getAutoNumberStart();
        statements.add("DROP SEQUENCE IF EXISTS " + schemaNamePrefix() + createNuoDBGeneratorSequenceName(table, column));
        statements.add("CREATE SEQUENCE " + schemaNamePrefix() + createNuoDBGeneratorSequenceName(table, autoNumbered) + " START WITH " + autoNumberStart);
        createTableStatement.append(" GENERATED BY DEFAULT AS IDENTITY(" + createNuoDBGeneratorSequenceName(table, autoNumbered) + ")");
      }

      if (column.isPrimaryKey()) {
        primaryKeys.add(column.getName());
      }

      first = false;
    }

    if (!primaryKeys.isEmpty()) {
      createTableStatement.append(", PRIMARY KEY (");
      createTableStatement.append(Joiner.on(", ").join(primaryKeys));
      createTableStatement.append(")");
    }

    createTableStatement.append(")");
    statements.add(createTableStatement.toString());

    return statements;
  }


  /**
   * Create a standard name for the NuoDB generator sequence, controlling the autonumbering
   */
  private String createNuoDBGeneratorSequenceName(Table table, Column column) {
    return  table.getName() + "_IDS_" + column.getAutoNumberStart();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#dropStatements(org.alfasoftware.morf.metadata.Table)
   */
  @Override
  public Collection<String> dropStatements(Table table) {
    if (table.isTemporary()) {
      TEMPORARY_TABLES.remove(table.getName());
    }

    List<String> dropList = new ArrayList<>();
    dropList.add("drop table " + qualifiedTableName(table));

    for (Column column : table.columns()) {
      if(column.isAutoNumbered()) {
        dropList.add("DROP SEQUENCE IF EXISTS " + schemaNamePrefix() +  createNuoDBGeneratorSequenceName(table, column));
      }
    }

    //NuoDB doesn't seem to always drop the indexes when dropping the tables.
    //We need to explicitly have these statements to prevent index clashes.
    //TODO WEB-57648
    for (Index index : table.indexes()) {
      dropList.add(optionalDropIndexStatement(table, index));
    }

    return dropList;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#truncateTableStatements(org.alfasoftware.morf.metadata.Table)
   */
  @Override
  public Collection<String> truncateTableStatements(Table table) {
    return Arrays.asList("truncate table " + qualifiedTableName(table));
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#deleteAllFromTableStatements(org.alfasoftware.morf.metadata.Table)
   */
  @Override
  public Collection<String> deleteAllFromTableStatements(Table table) {
    return Arrays.asList("delete from " + qualifiedTableName(table));
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getColumnRepresentation(org.alfasoftware.morf.metadata.DataType,
   *      int, int)
   */
  @Override
  protected String getColumnRepresentation(DataType dataType, int width, int scale) {
    switch (dataType) {
      case STRING:
        return String.format("VARCHAR(%d)", width);

      case DECIMAL:
        return String.format("DECIMAL(%d,%d)", width, scale);

      case DATE:
        return "DATE";

      case BOOLEAN:
        return "BOOLEAN";

      case BIG_INTEGER:
        return "BIGINT";

      case INTEGER:
        return "INTEGER";

      case BLOB:
        return "BLOB";

      case CLOB:
        return "NCLOB";

      default:
        throw new UnsupportedOperationException("Cannot map column with type [" + dataType + "]");
    }
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#useInsertBatching()
   */
  @Override
  public boolean useInsertBatching() {
    return true;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getFromDummyTable()
   */
  @Override
  protected String getFromDummyTable() {
    return " FROM dual ";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#connectionTestStatement()
   */
  @Override
  public String connectionTestStatement() {
    return "select 1 from dual";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getDatabaseType()
   */
  @Override
  public DatabaseType getDatabaseType() {
    return DatabaseType.Registry.findByIdentifier(NuoDB.IDENTIFIER);
  }


  @Override
  protected String getSqlFrom(LocalDate literalValue) {
    return String.format("DATE('%s')", literalValue.toString("yyyy-MM-dd"));
  }


  /**
   * We need to cast decimals as DECIMAL in NuoDB otherwise they're treated as strings.
   * TODO WEB-56758 - contacted NuoDB support on this point, issue ref: Request #6593
   * TODO WEB-58717 - A further fix to this is required to specify the correct precision and scale.
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlFrom(org.alfasoftware.morf.sql.element.FieldLiteral)
   */
  @Override
  protected String getSqlFrom(FieldLiteral field) {
    switch (field.getDataType()) {
      case DATE:
        return String.format("DATE('%s')", field.getValue());
      case DECIMAL:
        if (field.getValue().contains(".")) {
          String value = field.getValue();
          int scale = value.length() - 1;
          int precision = scale - value.indexOf('.');
          return String.format("CAST ('%s' AS DECIMAL(%s,%s))", field.getValue(), scale, precision);
        } else {
          return super.getSqlFrom(field);
        }
      default:
        return super.getSqlFrom(field);
    }
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlFrom(ConcatenatedField)
   */
  @Override
  protected String getSqlFrom(ConcatenatedField concatenatedField) {
    List<String> sql = new ArrayList<>();
    for (AliasedField field : concatenatedField.getConcatenationFields()) {
      // Interpret null values as empty strings
      sql.add("COALESCE(" + getSqlFrom(field) + ",'')");
    }
    return StringUtils.join(sql, " || ");
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForIsNull(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForIsNull(Function function) {
    return "COALESCE(" + getSqlFrom(function.getArguments().get(0)) + ", " + getSqlFrom(function.getArguments().get(1)) + ") ";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#buildAutonumberUpdate(org.alfasoftware.morf.sql.element.TableReference, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
   */
  @Override
  public List<String> buildAutonumberUpdate(TableReference dataTable, String fieldName, String idTableName, String nameColumn, String valueColumn) {
    String existingSelect = getExistingMaxAutoNumberValue(dataTable, fieldName);
    String tableName = getAutoNumberName(dataTable.getName());

    if (tableName.equals("autonumber")) {
      return Collections.emptyList();
    }

    return ImmutableList.of(
      String.format("INSERT INTO %s%s (%s, %s) VALUES('%s', (%s)) ON DUPLICATE KEY UPDATE nextValue = GREATEST(nextValue, VALUES(nextValue))",
        schemaNamePrefix(), idTableName, nameColumn, valueColumn, tableName, existingSelect)
    );
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#alterTableAddColumnStatements(org.alfasoftware.morf.metadata.Table, org.alfasoftware.morf.metadata.Column)
   */
  @Override
  public Collection<String> alterTableAddColumnStatements(Table table, Column column) {
    ImmutableList.Builder<String> statements = ImmutableList.builder();

    statements.add(
      new StringBuilder().append("ALTER TABLE ").append(qualifiedTableName(table)).append(" ADD COLUMN ")
        .append(column.getName()).append(' ').append(sqlRepresentationOfColumnType(column, true))
        .toString()
    );

    if (StringUtils.isNotBlank(column.getDefaultValue()) && column.isNullable()) {
      statements.add("UPDATE " + table.getName() + " SET " + column.getName() + " = " + getSqlFrom(new FieldLiteral(column.getDefaultValue(), column.getType())));
    }

    return statements.build();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#alterTableChangeColumnStatements(org.alfasoftware.morf.metadata.Table, org.alfasoftware.morf.metadata.Column, org.alfasoftware.morf.metadata.Column)
   * To modify the column we need to drop any indexes it's used for, change the column, and then recreate the index.
   */
  @Override
  public Collection<String> alterTableChangeColumnStatements(Table table, Column oldColumn, Column newColumn) {
    List<String> result = new ArrayList<>();
    List<Index> indexesToRebuild = indexesToDropWhenModifyingColumn(table, oldColumn);

    for(Index index : indexesToRebuild) {
      result.addAll(indexDropStatements(table, index));
    }

    if (oldColumn.isPrimaryKey()) {
      result.add(dropPrimaryKeyConstraintStatement(table));
    }

    // Rename has to happen BEFORE any operations on the newly renamed column
    if (!newColumn.getName().equals(oldColumn.getName())) {
      result.add("ALTER TABLE " + qualifiedTableName(table)
        + " RENAME COLUMN " + oldColumn.getName()
        + " TO " + newColumn.getName()
      );
    }

    if (oldColumn.getType() != newColumn.getType()
        ||oldColumn.getScale() != newColumn.getScale()
        ||oldColumn.getWidth() != newColumn.getWidth()
    ) {
        result.add(
          "ALTER TABLE " + qualifiedTableName(table) + " ALTER COLUMN " + newColumn.getName()
          + " TYPE " + sqlRepresentationOfColumnType(newColumn, false, false, true)
        );
    }

    result.addAll(changeColumnNullability(table, oldColumn, newColumn));

    if (oldColumn.isAutoNumbered() != newColumn.isAutoNumbered()) {
      // TODO we should also copy the data right?!
      result.addAll(alterTableDropColumnStatements(table, oldColumn));
      result.addAll(alterTableAddColumnStatements(table, newColumn));
    }

    // rebuild the PK
    List<Column> primaryKeys = primaryKeysForTable(table);
    if ((newColumn.isPrimaryKey() || oldColumn.isPrimaryKey()) && !primaryKeys.isEmpty()) {
      result.add(addPrimaryKeyConstraintStatement(table, namesOfColumns(primaryKeys)));
    }

    for(Index index : indexesToRebuild) {
      result.add(indexDeploymentStatement(table, index));
    }

    return result;
  }


  /**
   * Change the nullability and default value of the column
   */
  private List<String> changeColumnNullability(Table table, Column oldColumn, Column newColumn) {
    List<String> result = new ArrayList<>();
    if (StringUtils.isNotEmpty(newColumn.getDefaultValue())) {
      String escape = newColumn.getType() == DataType.STRING ? "'" : "";
      result.add(
        "ALTER TABLE " + qualifiedTableName(table) + " ALTER COLUMN " + newColumn.getName()
        + " NOT NULL" // required by the DEFAULT to update existing rows
        + " DEFAULT " + escape + newColumn.getDefaultValue() + escape
      );
    }
    else if (!StringUtils.equals(oldColumn.getDefaultValue(), newColumn.getDefaultValue())) {
      result.add(
        "ALTER TABLE " + qualifiedTableName(table) + " ALTER COLUMN " + newColumn.getName()
        + " DROP DEFAULT"
      );
      result.add(
        "ALTER TABLE " + qualifiedTableName(table) + " ALTER COLUMN " + newColumn.getName()
        + " " + (newColumn.isNullable() ? "NULL" : "NOT NULL")
      );
    }
    else if (oldColumn.isNullable() != newColumn.isNullable()) {
      result.add(
        "ALTER TABLE " + qualifiedTableName(table) + " ALTER COLUMN " + newColumn.getName()
        + " " + (newColumn.isNullable() ? "NULL" : "NOT NULL DEFAULT 0")
      );
      result.add(
        "ALTER TABLE " + qualifiedTableName(table) + " ALTER COLUMN " + newColumn.getName()
        + " DROP DEFAULT"
      );
    }
    return result;
  }


  private List<Index> indexesToDropWhenModifyingColumn(Table table, final Column column) {
    return FluentIterable.from(table.indexes()).filter(new Predicate<Index>() {
      @Override
      public boolean apply(Index input) {
        return input.columnNames().contains(column.getName());
      }
    }).toList();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#alterTableDropColumnStatements(org.alfasoftware.morf.metadata.Table, org.alfasoftware.morf.metadata.Column)
   */
  @Override
  public Collection<String> alterTableDropColumnStatements(Table table, Column column) {
    List<String> result = new ArrayList<>();

    if (column.isPrimaryKey()) {
      result.add(dropPrimaryKeyConstraintStatement(table));
    }

    StringBuilder statement = new StringBuilder()
      .append("ALTER TABLE ").append(qualifiedTableName(table))
      .append(" DROP COLUMN ").append(column.getName());

    result.add(statement.toString());
    result.add("DROP SEQUENCE IF EXISTS " + schemaNamePrefix() + createNuoDBGeneratorSequenceName(table, column));

    return result;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#changePrimaryKeyColumns(org.alfasoftware.morf.metadata.Table, java.util.List, java.util.List)
   */
  @Override
  public Collection<String> changePrimaryKeyColumns(Table table, List<String> oldPrimaryKeyColumns, List<String> newPrimaryKeyColumns) {
    List<String> result = new ArrayList<>();

    if (!oldPrimaryKeyColumns.isEmpty()) {
      result.add(dropPrimaryKeyConstraintStatement(table));
    }

    if (!newPrimaryKeyColumns.isEmpty()) {
      result.add(addPrimaryKeyConstraintStatement(table, newPrimaryKeyColumns));
    }

    return result;
  }


  /**
   * @param table The table to add the constraint for
   * @param primaryKeyColumnNames
   * @return The statement
   */
  private String addPrimaryKeyConstraintStatement(Table table, List<String> primaryKeyColumnNames) {
    return "ALTER TABLE " + qualifiedTableName(table) + " ADD PRIMARY KEY (" + Joiner.on(", ").join(primaryKeyColumnNames) + ")";
  }


  /**
   * @param table The table whose primary key should be dropped
   * @return The statement
   */
  private String dropPrimaryKeyConstraintStatement(Table table) {
    return "DROP INDEX IF EXISTS "+schemaNamePrefix(table)+"\"" + table.getName().toUpperCase() + "..PRIMARY_KEY\"";
  }


  /**
   * For each index to add, we need to ensure the old index has been dropped in NuoDB
   * @see org.alfasoftware.morf.jdbc.SqlDialect#addIndexStatements(org.alfasoftware.morf.metadata.Table, org.alfasoftware.morf.metadata.Index)
   */
  @Override
  public Collection<String> addIndexStatements(Table table, Index index) {
    return Arrays.asList(optionalDropIndexStatement(table, index), indexDeploymentStatement(table, index));
  }

  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#indexDeploymentStatements(org.alfasoftware.morf.metadata.Table,
   *      org.alfasoftware.morf.metadata.Index)
   */
  @Override
  protected String indexDeploymentStatement(Table table, Index index) {
    StringBuilder statement = new StringBuilder();

    statement.append("CREATE ");
    if (index.isUnique()) {
      statement.append("UNIQUE ");
    }
    return statement
      .append("INDEX ")
      .append(index.getName()) // we don't specify the schema - it's implicit
      .append(" ON ")
      .append(qualifiedTableName(table))
      .append(" (")
      .append(Joiner.on(',').join(index.columnNames()))
      .append(")")
      .toString();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#indexDropStatements(org.alfasoftware.morf.metadata.Table,
   *      org.alfasoftware.morf.metadata.Index)
   */
  @Override
  public Collection<String> indexDropStatements(Table table, Index indexToBeRemoved) {
    return Arrays.asList("DROP INDEX " + schemaNamePrefix(table) + indexToBeRemoved.getName());
  }


  /**
   * Creates an SQL statement which attempts to drop an index if it exists
   */
  private String optionalDropIndexStatement(Table table, Index indexToBeRemoved) {
    return "DROP INDEX IF EXISTS " + schemaNamePrefix(table) + indexToBeRemoved.getName();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#renameIndexStatements(org.alfasoftware.morf.metadata.Table, java.lang.String, java.lang.String)
   */
  @Override
  public Collection<String> renameIndexStatements(Table table, final String fromIndexName, final String toIndexName) {
    Index newIndex, existingIndex;

    try {
      newIndex = Iterables.find(table.indexes(), new Predicate<Index>() {
        @Override public boolean apply(Index input) {
          return input.getName().equals(toIndexName);
        }
      });

      existingIndex = newIndex.isUnique() ? index(fromIndexName).columns(newIndex.columnNames()).unique() :
        index(fromIndexName).columns(newIndex.columnNames());
    } catch (NoSuchElementException nsee) {
      // If the index wasn't found, we must have the old schema instead of the
      // new one so try the other way round
      existingIndex = Iterables.find(table.indexes(), new Predicate<Index>() {
        @Override public boolean apply(Index input) {
          return input.getName().equals(fromIndexName);
        }
      });

      newIndex = existingIndex.isUnique() ? index(toIndexName).columns(existingIndex.columnNames()).unique() :
        index(toIndexName).columns(existingIndex.columnNames());
    }

    return ImmutableList.<String>builder()
      .addAll(indexDropStatements(table, existingIndex))
      .add(indexDeploymentStatement(table, newIndex))
      .build();
  }


  @Override
  protected String getSqlForOrderByField(FieldReference orderByField) {
    switch (orderByField.getNullValueHandling().isPresent()
              ? orderByField.getNullValueHandling().get()
              : NullValueHandling.NONE) {
      case FIRST:
        return getSqlFrom(orderByField) + " IS NOT NULL, " + super.getSqlForOrderByField(orderByField);
      case LAST:
        return getSqlFrom(orderByField) + " IS NULL, " + super.getSqlForOrderByField(orderByField);
      case NONE:
      default:
        return super.getSqlForOrderByField(orderByField);
    }
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForOrderByFieldNullValueHandling(org.alfasoftware.morf.sql.element.FieldReference, java.lang.StringBuilder)
   */
  @Override
  protected String getSqlForOrderByFieldNullValueHandling(FieldReference orderByField) {
    return "";
  }


  @Override
  protected String escapeSql(String literalValue) {
    String escaped = StringEscapeUtils.escapeSql(literalValue);
    // we need to deal with a strange design with the \' escape but no \\ escape
    return StringUtils.replace(escaped, "\\'", "'||TRIM('\\ ')||''");
  }

  //   \''\'
  //   \\''''\\''

  // <ello wo\''\'>
  // <ello wo\\''\\'>


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#decorateTemporaryTableName(java.lang.String)
   */
  @Override
  public String decorateTemporaryTableName(String undecoratedName) {
    return TEMPORARY_TABLE_PREFIX + undecoratedName;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#dropStatements(org.alfasoftware.morf.metadata.View)
   */
  @Override
  public Collection<String> dropStatements(View view) {
    return Arrays.asList("DROP VIEW " + schemaNamePrefix() + view.getName() + " IF EXISTS CASCADE");
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForYYYYMMDDToDate(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForYYYYMMDDToDate(Function function) {
    AliasedField field = function.getArguments().get(0);
    return "DATE_FROM_STR(" + getSqlFrom(field) + ", 'yyyyMMdd')";
  }



  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForDateToYyyymmdd(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForDateToYyyymmdd(Function function) {
    String sqlExpression = getSqlFrom(function.getArguments().get(0));
    return String.format("CAST(DATE_TO_STR(%1$s, 'yyyyMMdd') AS INT)", sqlExpression);
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForDateToYyyymmddHHmmss(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForDateToYyyymmddHHmmss(Function function) {
    String sqlExpression = getSqlFrom(function.getArguments().get(0));
    // Example for CURRENT_TIMESTAMP() -> 2015-06-23 11:25:08.11
    return String.format("CAST(DATE_TO_STR(%1$s, 'yyyyMMddHHmmss') AS BIGINT)", sqlExpression);
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForNow(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForNow(Function function) {
    return "CURRENT_TIMESTAMP()";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForDaysBetween(org.alfasoftware.morf.sql.element.AliasedField,
   *      org.alfasoftware.morf.sql.element.AliasedField)
   */
  @Override
  protected String getSqlForDaysBetween(AliasedField toDate, AliasedField fromDate) {
    return "CAST(" + getSqlFrom(toDate) + " AS DATE) - CAST(" + getSqlFrom(fromDate) + " AS DATE)";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForMonthsBetween(org.alfasoftware.morf.sql.element.AliasedField, org.alfasoftware.morf.sql.element.AliasedField)
   */
  @Override
  protected String getSqlForMonthsBetween(AliasedField toDate, AliasedField fromDate) {
    String toDateStr = getSqlFrom(toDate);
    String fromDateStr = getSqlFrom(fromDate);
    return
       "(EXTRACT(YEAR FROM "+toDateStr+") - EXTRACT(YEAR FROM "+fromDateStr+")) * 12"
       + "+ (EXTRACT(MONTH FROM "+toDateStr+") - EXTRACT(MONTH FROM "+fromDateStr+"))"
       + "+ CASE WHEN "+toDateStr+" > "+fromDateStr
             + " THEN CASE WHEN EXTRACT(DAY FROM "+toDateStr+") >= EXTRACT(DAY FROM "+fromDateStr+") THEN 0"
                       + " WHEN EXTRACT(MONTH FROM "+toDateStr+") <> EXTRACT(MONTH FROM "+toDateStr+" + 1) THEN 0"
                       + " ELSE -1 END"
             + " ELSE CASE WHEN EXTRACT(MONTH FROM "+fromDateStr+") <> EXTRACT(MONTH FROM "+fromDateStr+" + 1) THEN 0"
                       + " WHEN EXTRACT(DAY FROM "+fromDateStr+") >= EXTRACT(DAY FROM "+toDateStr+") THEN 0"
                       + " ELSE 1 END"
             + " END"
       + "\n"
    ;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#leftTrim(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String leftTrim(Function function) {
    return "LTRIM(" + getSqlFrom(function.getArguments().get(0)) + ")";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#rightTrim(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String rightTrim(Function function) {
    return "RTRIM(" + getSqlFrom(function.getArguments().get(0)) + ")";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForAddDays(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForAddDays(Function function) {
    return String.format(
      "DATE_ADD(%s, INTERVAL %s DAY)",
      getSqlFrom(function.getArguments().get(0)),
      getSqlFrom(function.getArguments().get(1))
    );
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForAddMonths(org.alfasoftware.morf.sql.element.Function)
   */
  @Override
  protected String getSqlForAddMonths(Function function) {
    return String.format(
      "DATE_ADD(%s, INTERVAL %s MONTH)",
      getSqlFrom(function.getArguments().get(0)),
      getSqlFrom(function.getArguments().get(1))
        );
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#renameTableStatements(java.lang.String, java.lang.String)
   */
  @Override
  public Collection<String> renameTableStatements(Table from, Table to) {

    Builder<String> builder = ImmutableList.<String>builder();

    if (!primaryKeysForTable(from).isEmpty()) {
      builder.add(dropPrimaryKeyConstraintStatement(from));
    }

    builder.add("ALTER TABLE " + qualifiedTableName(from) + " RENAME TO " + qualifiedTableName(to));

    if (!primaryKeysForTable(to).isEmpty()) {
      builder.add(addPrimaryKeyConstraintStatement(to, namesOfColumns(primaryKeysForTable(to))));
    }

    return builder.build();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlFrom(org.alfasoftware.morf.sql.MergeStatement)
   */
  @Override
  protected String getSqlFrom(MergeStatement statement) {
    if (StringUtils.isBlank(statement.getTable().getName())) {
      throw new IllegalArgumentException("Cannot create SQL for a blank table");
    }

    checkSelectStatementHasNoHints(statement.getSelectStatement(), "MERGE may not be used with SELECT statement hints");

    final String destinationTableName = statement.getTable().getName();

    // Add the preamble
    StringBuilder sqlBuilder = new StringBuilder("INSERT INTO ");

    sqlBuilder.append(schemaNamePrefix(statement.getTable()));
    sqlBuilder.append(destinationTableName);
    sqlBuilder.append("(");
    Iterable<String> intoFields = Iterables.transform(statement.getSelectStatement().getFields(), new com.google.common.base.Function<AliasedField, String>() {
      @Override
      public String apply(AliasedField field) {
        return field.getImpliedName();
      }
    });
    sqlBuilder.append(Joiner.on(", ").join(intoFields));
    sqlBuilder.append(") ");

    // Add select statement
    sqlBuilder.append(getSqlFrom(statement.getSelectStatement()));

    // Note that we use the source select statement's fields here as we assume that they are appropriately
    // aliased to match the target table as part of the API contract (it's needed for other dialects)
    sqlBuilder.append(" ON DUPLICATE KEY UPDATE ");
    Iterable<String> setStatements = Iterables.transform(statement.getSelectStatement().getFields(), new com.google.common.base.Function<AliasedField, String>() {
      @Override
      public String apply(AliasedField field) {
      return String.format("%s = values(%s)", field.getImpliedName(), field.getImpliedName());
      }
    });
    sqlBuilder.append(Joiner.on(", ").join(setStatements));
    return sqlBuilder.toString();
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForRandomString(int)
   */
  @Override
  protected String getSqlForRandomString(Function function) {
    return String.format("SUBSTRING(CAST(RAND() AS STRING), 3, %s)", getSqlFrom(function.getArguments().get(0)));
  }


  @Override
  protected String getSqlForLeftPad(AliasedField field, AliasedField length, AliasedField character) {
    // this would be much nicer if written as DSL
    String paddingSql = "REPLACE('                    ', ' ', " + getSqlFrom(character) + ")";
    String padlengthSql = getSqlFrom(length) + " - LENGTH(CAST(" + getSqlFrom(field) + " AS STRING))";
    String padjoinSql = "SUBSTRING(" + paddingSql + ", 1, " + padlengthSql + ") || " + getSqlFrom(field);
    String padtrimSql = "SUBSTRING(" + getSqlFrom(field) + ", 1, " + getSqlFrom(length) + ")";
    return "CASE WHEN " + padlengthSql + " > 0 THEN " + padjoinSql + " ELSE " + padtrimSql + " END";
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#rebuildTriggers(org.alfasoftware.morf.metadata.Table)
   */
  @Override
  public Collection<String> rebuildTriggers(Table table) {
    return SqlDialect.NO_STATEMENTS;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#likeEscapeSuffix()
   */
  @Override
  protected String likeEscapeSuffix() {
    return ""; // the escape character is \ by default. We don't need to set it, and setting it appears to be challenging anyway as it is a general escape char.
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#supportsWindowFunctions()
   */
  @Override
  public boolean supportsWindowFunctions() {
    return true;
  }


  /**
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlForLastDayOfMonth(org.alfasoftware.morf.sql.element.AliasedField)
   */
  @Override
  protected String getSqlForLastDayOfMonth(AliasedField date) {
    return "DATE_SUB(DATE_ADD(DATE_SUB("+getSqlFrom(date)+", INTERVAL DAY("+getSqlFrom(date)+")-1 DAY), INTERVAL 1 MONTH), INTERVAL 1 DAY)";
  }


  /**
   * Override the core method to stop qualification of temporary tables
   */
  @Override
  protected String qualifiedTableName(Table table) {
    if (table.isTemporary()) {
      return table.getName(); // temporary tables do not exist in a schema
    } else {
      return super.qualifiedTableName(table);
    }
  }


  /**
   * NuoDB returns the type from a MathsFields as a String. We therefore need an explicit cast to NUMERIC.
   * This a fix for WEB-56829 and should be removed once NuoDB has been fixed.
   * @see org.alfasoftware.morf.jdbc.SqlDialect#getSqlFrom(MathsField)
   * @param field the MathsField to get the name of
   * @return a string which is the name of the function
   */
  @Override
  protected String getSqlFrom(MathsField field) {
    return String.format("CAST((%s %s %s) AS NUMBER)", getSqlFrom(field.getLeftField()), field.getOperator(), getSqlFrom(field.getRightField()));
  }
}
