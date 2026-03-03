package org.burnerwallet.storage;

/**
 * Abstraction over MIDP RecordStore for testability.
 * Production code uses MidpRecordStoreAdapter; tests use InMemoryRecordStoreAdapter.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public interface RecordStoreAdapter {

    /**
     * Open a named record store, optionally creating it if it does not exist.
     *
     * @param name   store name (max 32 chars on real MIDP)
     * @param create true to create the store if it does not exist
     * @throws Exception if the store cannot be opened
     */
    void open(String name, boolean create) throws Exception;

    /**
     * Retrieve a record by its ID.
     *
     * @param recordId 1-based record identifier
     * @return copy of the record data
     * @throws Exception if the store is not open or the record does not exist
     */
    byte[] getRecord(int recordId) throws Exception;

    /**
     * Add a new record to the store.
     *
     * @param data byte array to store
     * @return the 1-based ID assigned to the new record
     * @throws Exception if the store is not open
     */
    int addRecord(byte[] data) throws Exception;

    /**
     * Replace the contents of an existing record.
     *
     * @param recordId 1-based record identifier
     * @param data     new byte array contents
     * @throws Exception if the store is not open or the record does not exist
     */
    void setRecord(int recordId, byte[] data) throws Exception;

    /**
     * Return the number of records in the store.
     *
     * @return record count
     * @throws Exception if the store is not open
     */
    int getNumRecords() throws Exception;

    /**
     * Close the record store. The store can be reopened later.
     *
     * @throws Exception if the store is not open
     */
    void close() throws Exception;

    /**
     * Delete the record store and all its records permanently.
     *
     * @throws Exception if the store cannot be deleted
     */
    void deleteStore() throws Exception;
}
