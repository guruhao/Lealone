/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.dbobject.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.codefollower.lealone.command.Prepared;
import com.codefollower.lealone.constant.Constants;
import com.codefollower.lealone.constant.ErrorCode;
import com.codefollower.lealone.dbobject.DbObject;
import com.codefollower.lealone.dbobject.Right;
import com.codefollower.lealone.dbobject.Schema;
import com.codefollower.lealone.dbobject.SchemaObjectBase;
import com.codefollower.lealone.dbobject.Sequence;
import com.codefollower.lealone.dbobject.TriggerObject;
import com.codefollower.lealone.dbobject.constraint.Constraint;
import com.codefollower.lealone.dbobject.index.Index;
import com.codefollower.lealone.dbobject.index.IndexType;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.engine.UndoLogRecord;
import com.codefollower.lealone.expression.Expression;
import com.codefollower.lealone.expression.ExpressionVisitor;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.message.Trace;
import com.codefollower.lealone.result.Row;
import com.codefollower.lealone.result.RowList;
import com.codefollower.lealone.result.SearchRow;
import com.codefollower.lealone.result.SimpleRow;
import com.codefollower.lealone.result.SimpleRowValue;
import com.codefollower.lealone.result.SortOrder;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.value.CompareMode;
import com.codefollower.lealone.value.Value;
import com.codefollower.lealone.value.ValueNull;

/**
 * This is the base class for most tables.
 * A table contains a list of columns and a list of rows.
 */
public abstract class Table extends SchemaObjectBase {

    /**
     * The table type that means this table is a regular persistent table.
     */
    public static final int TYPE_CACHED = 0;

    /**
     * The table type that means this table is a regular persistent table.
     */
    public static final int TYPE_MEMORY = 1;

    /**
     * The table type name for linked tables.
     */
    public static final String TABLE_LINK = "TABLE LINK";

    /**
     * The table type name for system tables.
     */
    public static final String SYSTEM_TABLE = "SYSTEM TABLE";

    /**
     * The table type name for regular data tables.
     */
    public static final String TABLE = "TABLE";

    /**
     * The table type name for views.
     */
    public static final String VIEW = "VIEW";

    /**
     * The table type name for external table engines.
     */
    public static final String EXTERNAL_TABLE_ENGINE = "EXTERNAL";

    /**
     * The columns of this table.
     */
    protected Column[] columns;

    /**
     * The compare mode used for this table.
     */
    protected CompareMode compareMode;

    /**
     * Protected tables are not listed in the meta data and are excluded when
     * using the SCRIPT command.
     */
    protected boolean isHidden;

    /**
     * The table engine used (null for regular tables).
     */
    protected String tableEngine;

    private final HashMap<String, Column> columnMap;
    private final boolean persistIndexes;
    private final boolean persistData;
    private ArrayList<TriggerObject> triggers;
    private ArrayList<Constraint> constraints;
    private ArrayList<Sequence> sequences;
    private ArrayList<TableView> views;
    private boolean checkForeignKeyConstraints = true;
    private boolean onCommitDrop, onCommitTruncate;
    private Row nullRow;

    public Table(Schema schema, int id, String name, boolean persistIndexes, boolean persistData) {
        columnMap = schema.getDatabase().newStringMap();
        initSchemaObjectBase(schema, id, name, Trace.TABLE);
        this.persistIndexes = persistIndexes;
        this.persistData = persistData;
        compareMode = schema.getDatabase().getCompareMode();
    }

    public void rename(String newName) {
        super.rename(newName);
        if (constraints != null) {
            for (int i = 0, size = constraints.size(); i < size; i++) {
                Constraint constraint = constraints.get(i);
                constraint.rebuild();
            }
        }
    }

    /**
     * Lock the table for the given session.
     * This method waits until the lock is granted.
     *
     * @param session the session
     * @param exclusive true for write locks, false for read locks
     * @param force lock even in the MVCC mode
     * @throws DbException if a lock timeout occurred
     */
    public abstract void lock(Session session, boolean exclusive, boolean force);

    /**
     * Close the table object and flush changes.
     *
     * @param session the session
     */
    public abstract void close(Session session);

    /**
     * Release the lock for this session.
     *
     * @param s the session
     */
    public abstract void unlock(Session s);

    /**
     * Create an index for this table
     *
     * @param session the session
     * @param indexName the name of the index
     * @param indexId the id
     * @param cols the index columns
     * @param indexType the index type
     * @param create whether this is a new index
     * @param indexComment the comment
     * @return the index
     */
    public abstract Index addIndex(Session session, String indexName, int indexId, IndexColumn[] cols, IndexType indexType,
            boolean create, String indexComment);

    /**
     * Remove a row from the table and all indexes.
     *
     * @param session the session
     * @param row the row
     */
    public abstract void removeRow(Session session, Row row);

    /**
     * Remove all rows from the table and indexes.
     *
     * @param session the session
     */
    public abstract void truncate(Session session);

    /**
     * Add a row to the table and all indexes.
     *
     * @param session the session
     * @param row the row
     * @throws DbException if a constraint was violated
     */
    public abstract void addRow(Session session, Row row);

    /**
     * Commit an operation (when using multi-version concurrency).
     *
     * @param operation the operation
     * @param row the row
     */
    public void commit(short operation, Row row) {
        // nothing to do
    }

    /**
     * Check if this table supports ALTER TABLE.
     *
     * @throws DbException if it is not supported
     */
    public abstract void checkSupportAlter();

    /**
     * Get the table type name
     *
     * @return the table type name
     */
    public abstract String getTableType();

    /**
     * Get the scan index to iterate through all rows.
     *
     * @param session the session
     * @return the index
     */
    public abstract Index getScanIndex(Session session);

    /**
     * Get any unique index for this table if one exists.
     *
     * @return a unique index
     */
    public abstract Index getUniqueIndex();

    /**
     * Get all indexes for this table.
     *
     * @return the list of indexes
     */
    public abstract ArrayList<Index> getIndexes();

    /**
     * Check if this table is locked exclusively.
     *
     * @return true if it is.
     */
    public abstract boolean isLockedExclusively();

    /**
     * Get the last data modification id.
     *
     * @return the modification id
     */
    public abstract long getMaxDataModificationId();

    /**
     * Check if the table is deterministic.
     *
     * @return true if it is
     */
    public abstract boolean isDeterministic();

    /**
     * Check if the row count can be retrieved quickly.
     *
     * @return true if it can
     */
    public abstract boolean canGetRowCount();

    /**
     * Check if this table can be referenced.
     *
     * @return true if it can
     */
    public boolean canReference() {
        return true;
    }

    /**
     * Check if this table can be dropped.
     *
     * @return true if it can
     */
    public abstract boolean canDrop();

    /**
     * Get the row count for this table.
     *
     * @param session the session
     * @return the row count
     */
    public abstract long getRowCount(Session session);

    /**
     * Get the approximated row count for this table.
     *
     * @return the approximated row count
     */
    public abstract long getRowCountApproximation();

    public abstract long getDiskSpaceUsed();

    /**
     * Get the row id column if this table has one.
     *
     * @return the row id column, or null
     */
    public Column getRowIdColumn() {
        return null;
    }

    public String getCreateSQLForCopy(Table table, String quotedName) {
        throw DbException.throwInternalError();
    }

    /**
     * Add all objects that this table depends on to the hash set.
     *
     * @param dependencies the current set of dependencies
     */
    public void addDependencies(HashSet<DbObject> dependencies) {
        if (dependencies.contains(this)) {
            // avoid endless recursion
            return;
        }
        if (sequences != null) {
            for (Sequence s : sequences) {
                dependencies.add(s);
            }
        }
        ExpressionVisitor visitor = ExpressionVisitor.getDependenciesVisitor(dependencies);
        if (columns != null)
            for (Column col : columns) {
                col.isEverything(visitor);
            }
        if (constraints != null) {
            for (Constraint c : constraints) {
                c.isEverything(visitor);
            }
        }
        dependencies.add(this);
    }

    public ArrayList<DbObject> getChildren() {
        ArrayList<DbObject> children = New.arrayList();
        ArrayList<Index> indexes = getIndexes();
        if (indexes != null) {
            children.addAll(indexes);
        }
        if (constraints != null) {
            children.addAll(constraints);
        }
        if (triggers != null) {
            children.addAll(triggers);
        }
        if (sequences != null) {
            children.addAll(sequences);
        }
        if (views != null) {
            children.addAll(views);
        }
        ArrayList<Right> rights = database.getAllRights();
        for (Right right : rights) {
            if (right.getGrantedTable() == this) {
                children.add(right);
            }
        }
        return children;
    }

    protected void setColumns(Column[] columns) {
        setColumnsInternal(columns, true);
    }

    protected void setColumnsNoCheck(Column[] columns) {
        setColumnsInternal(columns, false);
    }

    private void setColumnsInternal(Column[] columns, boolean check) {
        this.columns = columns;
        if (columnMap.size() > 0) {
            columnMap.clear();
        }
        for (int i = 0; i < columns.length; i++) {
            Column col = columns[i];
            int dataType = col.getType();
            if (check && dataType == Value.UNKNOWN) {
                throw DbException.get(ErrorCode.UNKNOWN_DATA_TYPE_1, col.getSQL());
            }
            col.setTable(this, i);
            String columnName = col.getFullName();
            if (columnMap.get(columnName) != null) {
                throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1, columnName);
            }
            columnMap.put(columnName, col);
        }
    }

    /**
     * Rename a column of this table.
     *
     * @param column the column to rename
     * @param newName the new column name
     */
    public void renameColumn(Column column, String newName) {
        for (Column c : columns) {
            if (c == column) {
                continue;
            }
            if (c.getName().equals(newName)) {
                throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1, newName);
            }
        }
        columnMap.remove(column.getName());
        column.rename(newName);
        columnMap.put(newName, column);
    }

    /**
     * Check if the table is exclusively locked by this session.
     *
     * @param session the session
     * @return true if it is
     */
    public boolean isLockedExclusivelyBy(Session session) {
        return false;
    }

    /**
     * Update a list of rows in this table.
     *
     * @param prepared the prepared statement
     * @param session the session
     * @param rows a list of row pairs of the form old row, new row, old row,
     *            new row,...
     */
    public void updateRows(Prepared prepared, Session session, RowList rows) {
        // in case we need to undo the update
        int rollback = session.getUndoLogPos();
        // remove the old rows
        int rowScanCount = 0;
        for (rows.reset(); rows.hasNext();) {
            if ((++rowScanCount & 127) == 0) {
                prepared.checkCanceled();
            }
            Row o = rows.next();
            rows.next();
            removeRow(session, o);
            session.log(this, UndoLogRecord.DELETE, o);
        }
        // add the new rows
        for (rows.reset(); rows.hasNext();) {
            if ((++rowScanCount & 127) == 0) {
                prepared.checkCanceled();
            }
            rows.next();
            Row n = rows.next();
            try {
                addRow(session, n);
            } catch (DbException e) {
                if (e.getErrorCode() == ErrorCode.CONCURRENT_UPDATE_1) {
                    session.rollbackTo(rollback, false);
                }
                throw e;
            }
            session.log(this, UndoLogRecord.INSERT, n);
        }
    }

    public ArrayList<TableView> getViews() {
        return views;
    }

    public void removeChildrenAndResources(Session session) {
        while (views != null && views.size() > 0) {
            TableView view = views.get(0);
            views.remove(0);
            database.removeSchemaObject(session, view);
        }
        while (triggers != null && triggers.size() > 0) {
            TriggerObject trigger = triggers.get(0);
            triggers.remove(0);
            database.removeSchemaObject(session, trigger);
        }
        while (constraints != null && constraints.size() > 0) {
            Constraint constraint = constraints.get(0);
            constraints.remove(0);
            database.removeSchemaObject(session, constraint);
        }
        for (Right right : database.getAllRights()) {
            if (right.getGrantedTable() == this) {
                database.removeDatabaseObject(session, right);
            }
        }
        database.removeMeta(session, getId());
        // must delete sequences later (in case there is a power failure
        // before removing the table object)
        while (sequences != null && sequences.size() > 0) {
            Sequence sequence = sequences.get(0);
            sequences.remove(0);
            if (!isTemporary()) {
                // only remove if no other table depends on this sequence
                // this is possible when calling ALTER TABLE ALTER COLUMN
                if (database.getDependentTable(sequence, this) == null) {
                    database.removeSchemaObject(session, sequence);
                }
            }
        }
    }

    /**
     * Check that this column is not referenced by a multi-column constraint or
     * multi-column index. If it is, an exception is thrown. Single-column
     * references and indexes are dropped.
     *
     * @param session the session
     * @param col the column
     * @throws DbException if the column is referenced by multi-column
     *             constraints or indexes
     */
    public void dropSingleColumnConstraintsAndIndexes(Session session, Column col) {
        ArrayList<Constraint> constraintsToDrop = New.arrayList();
        if (constraints != null) {
            for (int i = 0, size = constraints.size(); i < size; i++) {
                Constraint constraint = constraints.get(i);
                HashSet<Column> columns = constraint.getReferencedColumns(this);
                if (!columns.contains(col)) {
                    continue;
                }
                if (columns.size() == 1) {
                    constraintsToDrop.add(constraint);
                } else {
                    throw DbException.get(ErrorCode.COLUMN_IS_REFERENCED_1, constraint.getSQL());
                }
            }
        }
        ArrayList<Index> indexesToDrop = New.arrayList();
        ArrayList<Index> indexes = getIndexes();
        if (indexes != null) {
            for (int i = 0, size = indexes.size(); i < size; i++) {
                Index index = indexes.get(i);
                if (index.getCreateSQL() == null) {
                    continue;
                }
                if (index.getColumnIndex(col) < 0) {
                    continue;
                }
                if (index.getColumns().length == 1) {
                    indexesToDrop.add(index);
                } else {
                    throw DbException.get(ErrorCode.COLUMN_IS_REFERENCED_1, index.getSQL());
                }
            }
        }
        for (Constraint c : constraintsToDrop) {
            session.getDatabase().removeSchemaObject(session, c);
        }
        for (Index i : indexesToDrop) {
            // the index may already have been dropped when dropping the constraint
            if (getIndexes().contains(i)) {
                session.getDatabase().removeSchemaObject(session, i);
            }
        }
    }

    public Row getTemplateRow() {
        return new Row(new Value[columns.length], Row.MEMORY_CALCULATE);
    }

    /**
     * Get a new simple row object.
     *
     * @param singleColumn if only one value need to be stored
     * @return the simple row object
     */
    public SearchRow getTemplateSimpleRow(boolean singleColumn) {
        if (singleColumn) {
            return new SimpleRowValue(columns.length);
        }
        return new SimpleRow(new Value[columns.length]);
    }

    synchronized Row getNullRow() {
        if (nullRow == null) {
            nullRow = new Row(new Value[columns.length], 1);
            for (int i = 0; i < columns.length; i++) {
                nullRow.setValue(i, ValueNull.INSTANCE);
            }
        }
        return nullRow;
    }

    public Column[] getColumns() {
        return columns;
    }

    public int getType() {
        return DbObject.TABLE_OR_VIEW;
    }

    /**
     * Get the column at the given index.
     *
     * @param index the column index (0, 1,...)
     * @return the column
     */
    public Column getColumn(int index) {
        return columns[index];
    }

    /**
     * Get the column with the given name.
     *
     * @param columnName the column name
     * @return the column
     * @throws DbException if the column was not found
     */
    public Column getColumn(String columnName) {
        Column column = columnMap.get(columnName);
        if (column == null) {
            throw DbException.get(ErrorCode.COLUMN_NOT_FOUND_1, columnName);
        }
        return column;
    }

    /**
     * Does the column with the given name exist?
     *
     * @param columnName the column name
     * @return true if the column exists
     */
    public boolean doesColumnExist(String columnName) {
        return columnMap.containsKey(columnName);
    }

    /**
     * Get the best plan for the given search mask.
     *
     * @param session the session
     * @param masks per-column comparison bit masks, null means 'always false',
     *              see constants in IndexCondition
     * @param sortOrder the sort order
     * @return the plan item
     */
    public PlanItem getBestPlanItem(Session session, int[] masks, SortOrder sortOrder) {
        PlanItem item = new PlanItem();
        item.setIndex(getScanIndex(session));
        item.cost = item.getIndex().getCost(session, null, null);
        ArrayList<Index> indexes = getIndexes();
        if (indexes != null && masks != null) {
            for (int i = 1, size = indexes.size(); i < size; i++) {
                Index index = indexes.get(i);
                double cost = index.getCost(session, masks, sortOrder);
                if (cost < item.cost) {
                    item.cost = cost;
                    item.setIndex(index);
                }
            }
        }
        return item;
    }

    /**
     * Get the primary key index if there is one, or null if there is none.
     *
     * @return the primary key index or null
     */
    public Index findPrimaryKey() {
        ArrayList<Index> indexes = getIndexes();
        if (indexes != null) {
            for (int i = 0, size = indexes.size(); i < size; i++) {
                Index idx = indexes.get(i);
                if (idx.getIndexType().isPrimaryKey()) {
                    return idx;
                }
            }
        }
        return null;
    }

    public Index getPrimaryKey() {
        Index index = findPrimaryKey();
        if (index != null) {
            return index;
        }
        throw DbException.get(ErrorCode.INDEX_NOT_FOUND_1, Constants.PREFIX_PRIMARY_KEY);
    }

    /**
     * Validate all values in this row, convert the values if required, and
     * update the sequence values if required. This call will also set the
     * default values if required and set the computed column if there are any.
     *
     * @param session the session
     * @param row the row
     */
    public void validateConvertUpdateSequence(Session session, Row row) {
        for (int i = 0; i < columns.length; i++) {
            Value value = row.getValue(i);
            Column column = columns[i];
            Value v2;
            if (column.getComputed()) {
                // force updating the value
                value = null;
                v2 = column.computeValue(session, row);
            }
            v2 = column.validateConvertUpdateSequence(session, value);
            if (v2 != value) {
                row.setValue(i, v2);
            }
        }
    }

    private static void remove(ArrayList<? extends DbObject> list, DbObject obj) {
        if (list != null) {
            int i = list.indexOf(obj);
            if (i >= 0) {
                list.remove(i);
            }
        }
    }

    /**
     * Remove the given index from the list.
     *
     * @param index the index to remove
     */
    public void removeIndex(Index index) {
        ArrayList<Index> indexes = getIndexes();
        if (indexes != null) {
            remove(indexes, index);
            if (index.getIndexType().isPrimaryKey()) {
                for (Column col : index.getColumns()) {
                    col.setPrimaryKey(false);
                }
            }
        }
    }

    /**
     * Remove the given view from the list.
     *
     * @param view the view to remove
     */
    public void removeView(TableView view) {
        remove(views, view);
    }

    /**
     * Remove the given constraint from the list.
     *
     * @param constraint the constraint to remove
     */
    public void removeConstraint(Constraint constraint) {
        remove(constraints, constraint);
    }

    /**
     * Remove a sequence from the table. Sequences are used as identity columns.
     *
     * @param session the session
     * @param sequence the sequence to remove
     */
    public void removeSequence(Sequence sequence) {
        remove(sequences, sequence);
    }

    /**
     * Remove the given trigger from the list.
     *
     * @param trigger the trigger to remove
     */
    public void removeTrigger(TriggerObject trigger) {
        remove(triggers, trigger);
    }

    /**
     * Add a view to this table.
     *
     * @param view the view to add
     */
    public void addView(TableView view) {
        views = add(views, view);
    }

    /**
     * Add a constraint to the table.
     *
     * @param constraint the constraint to add
     */
    public void addConstraint(Constraint constraint) {
        if (constraints == null || constraints.indexOf(constraint) < 0) {
            constraints = add(constraints, constraint);
        }
    }

    public ArrayList<Constraint> getConstraints() {
        return constraints;
    }

    /**
     * Add a sequence to this table.
     *
     * @param sequence the sequence to add
     */
    public void addSequence(Sequence sequence) {
        sequences = add(sequences, sequence);
    }

    /**
     * Add a trigger to this table.
     *
     * @param trigger the trigger to add
     */
    public void addTrigger(TriggerObject trigger) {
        triggers = add(triggers, trigger);
    }

    private static <T> ArrayList<T> add(ArrayList<T> list, T obj) {
        if (list == null) {
            list = New.arrayList();
        }
        // self constraints are two entries in the list
        list.add(obj);
        return list;
    }

    /**
     * Fire the triggers for this table.
     *
     * @param session the session
     * @param type the trigger type
     * @param beforeAction whether 'before' triggers should be called
     */
    public void fire(Session session, int type, boolean beforeAction) {
        if (triggers != null) {
            for (TriggerObject trigger : triggers) {
                trigger.fire(session, type, beforeAction);
            }
        }
    }

    /**
     * Check whether this table has a select trigger.
     *
     * @return true if it has
     */
    public boolean hasSelectTrigger() {
        if (triggers != null) {
            for (TriggerObject trigger : triggers) {
                if (trigger.isSelectTrigger()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if row based triggers or constraints are defined.
     * In this case the fire after and before row methods need to be called.
     *
     *  @return if there are any triggers or rows defined
     */
    public boolean fireRow() {
        return (constraints != null && constraints.size() > 0) || (triggers != null && triggers.size() > 0);
    }

    /**
     * Fire all triggers that need to be called before a row is updated.
     *
     * @param session the session
     * @param oldRow the old data or null for an insert
     * @param newRow the new data or null for a delete
     * @return true if no further action is required (for 'instead of' triggers)
     */
    public boolean fireBeforeRow(Session session, Row oldRow, Row newRow) {
        boolean done = fireRow(session, oldRow, newRow, true, false);
        fireConstraints(session, oldRow, newRow, true);
        return done;
    }

    private void fireConstraints(Session session, Row oldRow, Row newRow, boolean before) {
        if (constraints != null) {
            // don't use enhanced for loop to avoid creating objects
            for (int i = 0, size = constraints.size(); i < size; i++) {
                Constraint constraint = constraints.get(i);
                if (constraint.isBefore() == before) {
                    constraint.checkRow(session, this, oldRow, newRow);
                }
            }
        }
    }

    /**
     * Fire all triggers that need to be called after a row is updated.
     *
     *  @param session the session
     *  @param oldRow the old data or null for an insert
     *  @param newRow the new data or null for a delete
     *  @param rollback when the operation occurred within a rollback
     */
    public void fireAfterRow(Session session, Row oldRow, Row newRow, boolean rollback) {
        fireRow(session, oldRow, newRow, false, rollback);
        if (!rollback) {
            fireConstraints(session, oldRow, newRow, false);
        }
    }

    private boolean fireRow(Session session, Row oldRow, Row newRow, boolean beforeAction, boolean rollback) {
        if (triggers != null) {
            for (TriggerObject trigger : triggers) {
                boolean done = trigger.fireRow(session, oldRow, newRow, beforeAction, rollback);
                if (done) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isGlobalTemporary() {
        return false;
    }

    /**
     * Check if this table can be truncated.
     *
     * @return true if it can
     */
    public boolean canTruncate() {
        return false;
    }

    /**
     * Enable or disable foreign key constraint checking for this table.
     *
     * @param session the session
     * @param enabled true if checking should be enabled
     * @param checkExisting true if existing rows must be checked during this call
     */
    public void setCheckForeignKeyConstraints(Session session, boolean enabled, boolean checkExisting) {
        if (enabled && checkExisting) {
            if (constraints != null) {
                for (Constraint c : constraints) {
                    c.checkExistingData(session);
                }
            }
        }
        checkForeignKeyConstraints = enabled;
    }

    public boolean getCheckForeignKeyConstraints() {
        return checkForeignKeyConstraints;
    }

    /**
     * Get the index that has the given column as the first element.
     * This method returns null if no matching index is found.
     *
     * @param first if the min value should be returned
     * @return the index or null
     */
    public Index getIndexForColumn(Column column) {
        ArrayList<Index> indexes = getIndexes();
        if (indexes != null) {
            for (int i = 1, size = indexes.size(); i < size; i++) {
                Index index = indexes.get(i);
                if (index.canGetFirstOrLast()) {
                    int idx = index.getColumnIndex(column);
                    if (idx == 0) {
                        return index;
                    }
                }
            }
        }
        return null;
    }

    public boolean getOnCommitDrop() {
        return onCommitDrop;
    }

    public void setOnCommitDrop(boolean onCommitDrop) {
        this.onCommitDrop = onCommitDrop;
    }

    public boolean getOnCommitTruncate() {
        return onCommitTruncate;
    }

    public void setOnCommitTruncate(boolean onCommitTruncate) {
        this.onCommitTruncate = onCommitTruncate;
    }

    /**
     * If the index is still required by a constraint, transfer the ownership to
     * it. Otherwise, the index is removed.
     *
     * @param session the session
     * @param index the index that is no longer required
     */
    public void removeIndexOrTransferOwnership(Session session, Index index) {
        boolean stillNeeded = false;
        if (constraints != null) {
            for (Constraint cons : constraints) {
                if (cons.usesIndex(index)) {
                    cons.setIndexOwner(index);
                    database.update(session, cons);
                    stillNeeded = true;
                }
            }
        }
        if (!stillNeeded) {
            database.removeSchemaObject(session, index);
        }
    }

    /**
     * Check if a deadlock occurred. This method is called recursively. There is
     * a circle if the session to be tested has already being visited. If this
     * session is part of the circle (if it is the clash session), the method
     * must return an empty object array. Once a deadlock has been detected, the
     * methods must add the session to the list. If this session is not part of
     * the circle, or if no deadlock is detected, this method returns null.
     *
     * @param session the session to be tested for
     * @param clash set with sessions already visited, and null when starting
     *            verification
     * @param visited set with sessions already visited, and null when starting
     *            verification
     * @return an object array with the sessions involved in the deadlock, or
     *         null
     */
    public ArrayList<Session> checkDeadlock(Session session, Session clash, Set<Session> visited) {
        return null;
    }

    public boolean isPersistIndexes() {
        return persistIndexes;
    }

    public boolean isPersistData() {
        return persistData;
    }

    /**
     * Compare two values with the current comparison mode. The values may be of
     * different type.
     *
     * @param a the first value
     * @param b the second value
     * @return 0 if both values are equal, -1 if the first value is smaller, and
     *         1 otherwise
     */
    public int compareTypeSave(Value a, Value b) {
        if (a == b) {
            return 0;
        }
        int dataType = Value.getHigherOrder(a.getType(), b.getType());
        a = a.convertTo(dataType);
        b = b.convertTo(dataType);
        return a.compareTypeSave(b, compareMode);
    }

    public CompareMode getCompareMode() {
        return compareMode;
    }

    /**
     * Tests if the table can be written. Usually, this depends on the
     * database.checkWritingAllowed method, but some tables (eg. TableLink)
     * overwrite this default behaviour.
     */
    public void checkWritingAllowed() {
        database.checkWritingAllowed();
    }

    /**
     * Get or generate a default value for the given column.
     *
     * @param session the session
     * @param column the column
     * @return the value
     */
    public Value getDefaultValue(Session session, Column column) {
        Expression defaultExpr = column.getDefaultExpression();
        Value v;
        if (defaultExpr == null) {
            v = column.validateConvertUpdateSequence(session, null);
        } else {
            v = defaultExpr.getValue(session);
        }
        return column.convert(v);
    }

    public boolean isHidden() {
        return isHidden;
    }

    public void setHidden(boolean hidden) {
        this.isHidden = hidden;
    }

    public boolean isMVStore() {
        return false;
    }

    public boolean supportsColumnFamily() {
        return false;
    }

    public boolean supportsAlterColumnWithCopyData() {
        return true;
    }

    public boolean isDistributed() {
        return false;
    }

    public String getRowKeyName() {
        return null;
    }

    public boolean isStatic() {
        return true;
    }

    public Column getRowKeyColumn() {
        return null;
    }

    public String getFullColumnName(String columnFamilyName, String columnName) {
        return null;
    }

    public Column getColumn(String columnFamilyName, String columnName, boolean isInsert) {
        return getColumn(columnName);
    }

    public String getTableEngine() {
        return tableEngine;
    }

    public void setTableEngine(String tableEngine) {
        this.tableEngine = tableEngine;
    }

    public void addColumn(Column column) {
    }

    public void dropColumn(Column column) {
    }

    public boolean doesColumnFamilyExist(String columnFamilyName) {
        return false;
    }
}
