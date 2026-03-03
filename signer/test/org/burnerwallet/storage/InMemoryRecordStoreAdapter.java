package org.burnerwallet.storage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * In-memory test double for {@link RecordStoreAdapter}.
 * Simulates MIDP RecordStore behavior without requiring a real device or emulator.
 *
 * <p>Stores survive open/close cycles (like real RMS) via a static registry.
 * Call {@link #resetAll()} in test setUp/tearDown to ensure isolation.</p>
 */
public class InMemoryRecordStoreAdapter implements RecordStoreAdapter {

    /**
     * Static registry of all stores, keyed by store name.
     * Each store maps 1-based record IDs to byte array data.
     */
    private static final HashMap/*<String, HashMap<Integer, byte[]>>*/ registry = new HashMap();

    /** Next record ID per store name. */
    private static final HashMap/*<String, Integer>*/ nextIdMap = new HashMap();

    /** Name of the currently opened store, or null if not open. */
    private String currentName;

    /** Whether this adapter currently has a store open. */
    private boolean isOpen;

    /**
     * Clear all stores from the static registry.
     * Call this in test setUp/tearDown to ensure test isolation.
     */
    public static void resetAll() {
        registry.clear();
        nextIdMap.clear();
    }

    public void open(String name, boolean create) throws Exception {
        if (isOpen) {
            throw new Exception("Store already open: " + currentName);
        }
        if (name == null || name.length() == 0) {
            throw new Exception("Store name must not be null or empty");
        }

        boolean exists = registry.containsKey(name);
        if (!exists && !create) {
            throw new Exception("Store not found: " + name);
        }
        if (!exists) {
            registry.put(name, new HashMap());
            nextIdMap.put(name, 1);
        }

        currentName = name;
        isOpen = true;
    }

    public byte[] getRecord(int recordId) throws Exception {
        ensureOpen();
        HashMap store = getStore();
        byte[] data = (byte[]) store.get(recordId);
        if (data == null) {
            throw new Exception("Record not found: " + recordId);
        }
        // Return a copy, like real RMS
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    public int addRecord(byte[] data) throws Exception {
        ensureOpen();
        HashMap store = getStore();
        int id = (Integer) nextIdMap.get(currentName);
        // Store a copy, like real RMS
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        store.put(id, copy);
        nextIdMap.put(currentName, id + 1);
        return id;
    }

    public void setRecord(int recordId, byte[] data) throws Exception {
        ensureOpen();
        HashMap store = getStore();
        if (!store.containsKey(recordId)) {
            throw new Exception("Record not found: " + recordId);
        }
        // Store a copy, like real RMS
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        store.put(recordId, copy);
    }

    public int getNumRecords() throws Exception {
        ensureOpen();
        return getStore().size();
    }

    public void close() throws Exception {
        ensureOpen();
        currentName = null;
        isOpen = false;
    }

    public void deleteStore() throws Exception {
        if (!isOpen) {
            throw new Exception("No store is open");
        }
        String name = currentName;
        currentName = null;
        isOpen = false;
        registry.remove(name);
        nextIdMap.remove(name);
    }

    // ---- Internal helpers ----

    private void ensureOpen() throws Exception {
        if (!isOpen) {
            throw new Exception("No store is open");
        }
    }

    private HashMap getStore() {
        return (HashMap) registry.get(currentName);
    }
}
