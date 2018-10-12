package net.ravendb.embedded;

import net.ravendb.client.documents.conventions.DocumentConventions;
import net.ravendb.client.serverwide.DatabaseRecord;

@SuppressWarnings("unused")
public class DatabaseOptions {

    private boolean skipCreatingDatabase;
    private DocumentConventions conventions;
    private DatabaseRecord databaseRecord;

    public DatabaseOptions(DatabaseRecord databaseRecord) {
        this.databaseRecord = databaseRecord;
    }

    public DatabaseOptions(String databaseName) {
        this(new DatabaseRecord(databaseName));
    }

    public boolean isSkipCreatingDatabase() {
        return skipCreatingDatabase;
    }

    public void setSkipCreatingDatabase(boolean skipCreatingDatabase) {
        this.skipCreatingDatabase = skipCreatingDatabase;
    }

    public DocumentConventions getConventions() {
        return conventions;
    }

    public void setConventions(DocumentConventions conventions) {
        this.conventions = conventions;
    }

    public DatabaseRecord getDatabaseRecord() {
        return databaseRecord;
    }

    public void setDatabaseRecord(DatabaseRecord databaseRecord) {
        this.databaseRecord = databaseRecord;
    }
}
