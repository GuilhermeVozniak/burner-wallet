package org.burnerwallet.storage;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.RecordStoreNotFoundException;

/**
 * Production RecordStoreAdapter backed by MIDP javax.microedition.rms.RecordStore.
 *
 * Java 1.4 compatible (CLDC 1.1).
 */
public class MidpRecordStoreAdapter implements RecordStoreAdapter {
    private RecordStore rs;
    private String storeName;

    public void open(String name, boolean create) throws Exception {
        this.storeName = name;
        try {
            rs = RecordStore.openRecordStore(name, create);
        } catch (RecordStoreNotFoundException e) {
            throw new Exception("RecordStore not found: " + name);
        } catch (RecordStoreException e) {
            throw new Exception("Failed to open RecordStore: " + e.getMessage());
        }
    }

    public byte[] getRecord(int recordId) throws Exception {
        try {
            return rs.getRecord(recordId);
        } catch (Exception e) {
            throw new Exception("Failed to get record " + recordId + ": " + e.getMessage());
        }
    }

    public int addRecord(byte[] data) throws Exception {
        try {
            return rs.addRecord(data, 0, data.length);
        } catch (Exception e) {
            throw new Exception("Failed to add record: " + e.getMessage());
        }
    }

    public void setRecord(int recordId, byte[] data) throws Exception {
        try {
            rs.setRecord(recordId, data, 0, data.length);
        } catch (Exception e) {
            throw new Exception("Failed to set record " + recordId + ": " + e.getMessage());
        }
    }

    public int getNumRecords() throws Exception {
        try {
            return rs.getNumRecords();
        } catch (Exception e) {
            throw new Exception("Failed to get record count: " + e.getMessage());
        }
    }

    public void close() throws Exception {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (Exception e) {
                throw new Exception("Failed to close: " + e.getMessage());
            }
            rs = null;
        }
    }

    public void deleteStore() throws Exception {
        close();
        if (storeName != null) {
            try {
                RecordStore.deleteRecordStore(storeName);
            } catch (RecordStoreNotFoundException e) {
                /* already deleted — ignore */
            } catch (RecordStoreException e) {
                throw new Exception("Failed to delete: " + e.getMessage());
            }
        }
    }
}
