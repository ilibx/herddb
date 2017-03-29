/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.index.blink;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import herddb.core.AbstractIndexManager;
import herddb.core.MemoryManager;
import herddb.core.PostCheckpointAction;
import herddb.index.IndexOperation;
import herddb.index.KeyToPageIndex;
import herddb.index.PrimaryIndexPrefixScan;
import herddb.index.PrimaryIndexSeek;
import herddb.index.blink.BLink.EverBiggerKey;
import herddb.index.blink.BLink.SizeEvaluator;
import herddb.index.blink.BLinkMetadata.BLinkNodeMetadata;
import herddb.log.LogSequenceNumber;
import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import herddb.model.TableContext;
import herddb.storage.DataStorageManager;
import herddb.storage.DataStorageManagerException;
import herddb.storage.IndexStatus;
import herddb.utils.Bytes;
import herddb.utils.ExtendedDataInputStream;
import herddb.utils.ExtendedDataOutputStream;
import herddb.utils.VisibleByteArrayOutputStream;

/**
 * Implementation of {@link KeyToPageIndex} with a backing {@link BLink} paged and stored to
 * {@link DataStorageManager}.
 *
 * @author diego.salvi
 */
public class BLinkKeyToPageIndex implements KeyToPageIndex {

    private static final Logger LOGGER = Logger.getLogger(BLinkKeyToPageIndex.class.getName());

    public static final byte METADATA_PAGE = 0;
    public static final byte INNER_NODE_PAGE = 1;
    public static final byte LEAF_NODE_PAGE = 2;

    private static final byte NODE_PAGE_END_BLOCK = 0;
    private static final byte NODE_PAGE_KEY_VALUE_BLOCK = 1;
    private static final byte NODE_PAGE_INF_BLOCK = 2;

    private static final int METADATA_PAGE_END_BLOCK = 0;
    private static final int METADATA_PAGE_NODE_BLOCK = 1;

    private final String tableSpace;
    private final String indexName;

    private final MemoryManager memoryManager;
    private final DataStorageManager dataStorageManager;

    private final AtomicLong newPageId;

    private final BLinkIndexDataStorage<Bytes,Long> indexDataStorage;

    public BLink<Bytes,Long> tree;

    public BLinkKeyToPageIndex(String tableSpace, String tableName, MemoryManager memoryManager, DataStorageManager dataStorageManager) {
        super();
        this.tableSpace = tableSpace;
        this.indexName = tableName + "_primary";

        this.memoryManager = memoryManager;
        this.dataStorageManager = dataStorageManager;

        this.newPageId = new AtomicLong(1);
        this.indexDataStorage = new BLinkIndexDataStorageImpl();
    }

    @Override
    public long size() {
        return tree.size();
    }

    @Override
    public Long put(Bytes key, Long currentPage) {
        return tree.insert(key, currentPage);
    }

    @Override
    public boolean containsKey(Bytes key) {
        return tree.search(key) != null;
    }

    @Override
    public Long get(Bytes key) {
        return tree.search(key);
    }

    @Override
    public Long remove(Bytes key) {
        return tree.delete(key);
    }

    @Override
    public Stream<Entry<Bytes, Long>> scanner(IndexOperation operation, StatementEvaluationContext context,
        TableContext tableContext, AbstractIndexManager index) throws DataStorageManagerException {
        if (operation instanceof PrimaryIndexSeek) {
            try {
                PrimaryIndexSeek seek = (PrimaryIndexSeek) operation;
                byte[] seekValue = seek.value.computeNewValue(null, context, tableContext);
                if (seekValue == null) {
                    return Stream.empty();
                }
                Bytes key = Bytes.from_array(seekValue);
                Long pageId = tree.search(key);
                if (pageId == null) {
                    return Stream.empty();
                }
                return Stream.of(new AbstractMap.SimpleImmutableEntry<>(key, pageId));
            } catch (StatementExecutionException err) {
                throw new DataStorageManagerException(err);
            }
        }

        if (operation instanceof PrimaryIndexPrefixScan) {

            PrimaryIndexPrefixScan scan = (PrimaryIndexPrefixScan) operation;
//            SQLRecordKeyFunction value = sis.value;
            byte[] refvalue = scan.value.computeNewValue(null, context, tableContext);
            Bytes firstKey = Bytes.from_array(refvalue);
            Bytes lastKey = firstKey.next();

            return tree.scan(firstKey, lastKey);
        }

        // Remember that the IndexOperation can return more records
        // every predicate (WHEREs...) will always be evaluated anyway on every record, in order to guarantee correctness
        if (index != null) {
            try {
                return index.recordSetScanner(operation, context, tableContext, this);
            } catch (StatementExecutionException err) {
                throw new DataStorageManagerException(err);
            }
        }

        if (operation == null) {
            Stream<Map.Entry<Bytes, Long>> baseStream = tree.scan(null,null);
            return baseStream;
        }

        throw new DataStorageManagerException("operation " + operation + " not implemented on " + this.getClass());

    }

    @Override
    public void close() {
        final BLink<Bytes,Long> tree = this.tree;
        this.tree = null;
        if (tree != null) {
            tree.close();
        }
    }

    @Override
    public void truncate() {
        tree.truncate();
    }

    @Override
    public long getUsedMemory() {
        return 0;
    }

    @Override
    public boolean requireLoadAtStartup() {
        return false;
    }

    @Override
    public void start() throws DataStorageManagerException {
        LOGGER.log(Level.SEVERE, " start index {0}", new Object[]{indexName});

        /* Actually the same size */
        final long pageSize = memoryManager.getMaxLogicalPageSize();

        IndexStatus status = dataStorageManager.getLatestIndexStatus(tableSpace, indexName);

        if (status.sequenceNumber == LogSequenceNumber.START_OF_TIME) {
            tree = new BLink<>(pageSize, SizeEvaluatorImpl.INSTANCE,
                    memoryManager.getIndexPageReplacementPolicy(), indexDataStorage);

            /* Empty index */
            LOGGER.log(Level.SEVERE, "loaded empty index {0}", new Object[]{indexName});
        } else {

            try {
                BLinkMetadata<Bytes> metadata = MetadataSerializer.INSTANCE.read(status.indexData);

                tree = new BLink<>(pageSize, SizeEvaluatorImpl.INSTANCE,
                        memoryManager.getIndexPageReplacementPolicy(), indexDataStorage,
                        metadata);
            } catch (IOException e) {
                throw new DataStorageManagerException(e);
            }

            newPageId.set(status.newPageId);
            LOGGER.log(Level.SEVERE, "loaded index {0}: {1} keys", new Object[]{indexName, tree.size()});
        }
    }

    @Override
    public List<PostCheckpointAction> checkpoint(LogSequenceNumber sequenceNumber) throws DataStorageManagerException {

        try {

            /* Tree can be null if no data was inserted (tree creation deferred to check evaluate key size) */
            final BLink<Bytes,Long> tree = this.tree;
            if (tree == null) {
                return Collections.emptyList();
            }

            BLinkMetadata<Bytes> metadata = tree.checkpoint();

            byte[] metaPage = MetadataSerializer.INSTANCE.write(metadata);

            Set<Long> activePages = new HashSet<>();
            metadata.nodes.forEach(node -> activePages.add(node.storeId));

            IndexStatus indexStatus = new IndexStatus(indexName, sequenceNumber, newPageId.get(), activePages, metaPage);
            List<PostCheckpointAction> result = new ArrayList<>();
            result.addAll(dataStorageManager.indexCheckpoint(tableSpace, indexName, indexStatus));

            LOGGER.log(Level.SEVERE, "checkpoint index {0} finished, {1} blocks, pages {2}", new Object[]{
                indexName, Integer.toString(metadata.nodes.size()), activePages.toString()});

            return result;

        } catch (IOException err) {
            throw new DataStorageManagerException(err);
        }
    }

    private static final class SizeEvaluatorImpl implements SizeEvaluator<Bytes,Long> {

        /** Siongleton INSTANCE */
        public static final SizeEvaluator<Bytes,Long> INSTANCE = new SizeEvaluatorImpl();

        /** Private constructor, use Singleton instance {@link #INSTANCE}*/
        private SizeEvaluatorImpl() {}

        @Override
        public long evaluateKey(Bytes key) {
            return key.getEstimatedSize();
        }

        @Override
        public long evaluateValue(Long value) {
            /**
             * <pre>
             * java.lang.Long object internals:
             *  OFFSET  SIZE   TYPE DESCRIPTION                               VALUE
             *       0    12        (object header)                           N/A
             *      12     4        (alignment/padding gap)
             *      16     8   long Long.value                                N/A
             * Instance size: 24 bytes
             * Space losses: 4 bytes internal + 0 bytes external = 4 bytes total
             * </pre>
             */
            return 24L;
        }

        @Override
        public long evaluateAll(Bytes key, Long value) {
            return key.getEstimatedSize() + 24L;
        }
    }

    private static final class MetadataSerializer {

        public static final MetadataSerializer INSTANCE = new MetadataSerializer();

        public byte[] write(BLinkMetadata<Bytes> metadata) throws IOException {

            final VisibleByteArrayOutputStream bos = new VisibleByteArrayOutputStream();
            try ( ExtendedDataOutputStream edos = new ExtendedDataOutputStream(bos)) {

                /* flags for future implementations, actually unused */
                edos.writeVLong(0L);
                edos.writeByte(METADATA_PAGE);

                edos.writeVLong(metadata.nextID);

                edos.writeVLong(metadata.fast);
                edos.writeVInt(metadata.fastheight);

                edos.writeVLong(metadata.top);
                edos.writeVInt(metadata.topheight);

                edos.writeVLong(metadata.first);
                edos.writeVLong(metadata.values);

                for (BLinkNodeMetadata<Bytes> node : metadata.nodes) {

                    edos.writeVInt(METADATA_PAGE_NODE_BLOCK);

                    edos.writeBoolean(node.leaf);

                    edos.writeVLong(node.id);
                    edos.writeVLong(node.storeId);

                    edos.writeBoolean(node.empty);

                    edos.writeVInt(node.keys);
                    edos.writeVLong(node.bytes);

                    edos.writeZLong(node.outlink);
                    edos.writeZLong(node.rightlink);


                    boolean hasInf = node.rightsep == EverBiggerKey.INSTANCE;

                    edos.writeBoolean(hasInf);

                    if (!hasInf) {
                        edos.writeArray(((Bytes) node.rightsep).to_array());
                    }
                }

                edos.writeVInt(METADATA_PAGE_END_BLOCK);

            }

            return bos.toByteArray();

        }

        @SuppressWarnings("unchecked")
        @SuppressFBWarnings(value="DLS_DEAD_LOCAL_STORE", justification="flags still not used but it must be forcefully read")
        public BLinkMetadata<Bytes> read(byte[] data) throws IOException {

            try ( ByteArrayInputStream bis = new ByteArrayInputStream(data);
                  ExtendedDataInputStream edis = new ExtendedDataInputStream(bis) ) {

                /* flags for future implementations, actually unused */
                @SuppressWarnings("unused")
                long flags = edis.readVLong();
                byte rtype = edis.readByte();

                if (rtype != METADATA_PAGE) {
                    throw new IOException("Wrong page type " + rtype + " expected " + METADATA_PAGE );
                }

                long nextID = edis.readVLong();

                long fast = edis.readVLong();
                int fastheight = edis.readVInt();

                long top = edis.readVLong();
                int topheight = edis.readVInt();

                long first = edis.readVLong();
                long values = edis.readVLong();

                List<BLinkNodeMetadata<Bytes>> nodes = new LinkedList<>();

                int block;

                while ((block = edis.readVInt()) != METADATA_PAGE_END_BLOCK) {

                    if (block != METADATA_PAGE_NODE_BLOCK) {
                        throw new IOException("Wrong block type " + block);
                    }

                    final boolean leaf = edis.readBoolean();

                    long id = edis.readVLong();
                    long storeId = edis.readVLong();

                    boolean empty = edis.readBoolean();

                    int keys = edis.readVInt();
                    long bytes = edis.readVLong();

                    long outlink = edis.readZLong();
                    long rightlink = edis.readZLong();

                    boolean hasInf = edis.readBoolean();

                    Comparable<Bytes> rightsep;
                    if (hasInf) {
                        rightsep = EverBiggerKey.INSTANCE;
                    } else {
                        rightsep = Bytes.from_array(edis.readArray());
                    }

                    BLinkNodeMetadata<Bytes> node =
                            new BLinkNodeMetadata<>(leaf, id, storeId, empty, keys, bytes, outlink, rightlink, rightsep);

                    nodes.add(node);
                }

                return new BLinkMetadata<>(nextID, fast, fastheight, top, topheight, first, values, nodes);
            }

        }
    }
//    private final class MetadataStorage {
//
//        public long write(BLinkMetadata<Bytes> metadata) throws IOException {
//
//            final VisibleByteArrayOutputStream bos = new VisibleByteArrayOutputStream();
//            try (ExtendedDataOutputStream edos = new ExtendedDataOutputStream(bos)) {
//
//                /* flags for future implementations, actually unused */
//                edos.writeVLong(0L);
//                edos.writeByte(METADATA_PAGE);
//
//                edos.writeVLong(metadata.nextID);
//
//                edos.writeVLong(metadata.fast);
//                edos.writeVInt(metadata.fastheight);
//
//                edos.writeVLong(metadata.top);
//                edos.writeVInt(metadata.topheight);
//
//                edos.writeVLong(metadata.first);
//                edos.writeVLong(metadata.values);
//
//                for (BLinkNodeMetadata<Bytes> node : metadata.nodes) {
//
//                    edos.writeVInt(METADATA_PAGE_NODE_BLOCK);
//
//                    edos.writeBoolean(node.leaf);
//
//                    edos.writeVLong(node.id);
//                    edos.writeVLong(node.storeId);
//
//                    edos.writeBoolean(node.empty);
//
//                    edos.writeVInt(node.keys);
//                    edos.writeVLong(node.bytes);
//
//                    edos.writeZLong(node.outlink);
//                    edos.writeZLong(node.rightlink);
//
//
//                    boolean hasInf = node.rightsep == EverBiggerKey.INSTANCE;
//
//                    edos.writeBoolean(hasInf);
//
//                    if (!hasInf) {
//                        edos.writeArray(((Bytes) node.rightsep).to_array());
//                    }
//                }
//
//                edos.writeVInt(METADATA_PAGE_END_BLOCK);
//
//            }
//
//            long pageId = pageIDGenerator.incrementAndGet();
//
//            dataStorageManager.writeIndexPage(tableSpace, indexName, pageId, bos.getBuffer(), 0, bos.size());
//
//            return pageId;
//
//        }
//
//        @SuppressWarnings("unchecked")
//        @SuppressFBWarnings(value="DLS_DEAD_LOCAL_STORE", justification="flags still not used but it must be forcefully read")
//        public BLinkMetadata<Bytes> read(long pageId) throws IOException {
//
//
//            byte[] data = dataStorageManager.readIndexPage(tableSpace, indexName, pageId);
//
//            try ( ByteArrayInputStream bis = new ByteArrayInputStream(data);
//                  ExtendedDataInputStream edis = new ExtendedDataInputStream(bis) ) {
//
//                /* flags for future implementations, actually unused */
//                @SuppressWarnings("unused")
//                long flags = edis.readVLong();
//                byte rtype = edis.readByte();
//
//                if (rtype != METADATA_PAGE) {
//                    throw new IOException("Wrong page type " + rtype + " expected " + METADATA_PAGE );
//                }
//
//                long nextID = edis.readVLong();
//
//                long fast = edis.readVLong();
//                int fastheight = edis.readVInt();
//
//                long top = edis.readVLong();
//                int topheight = edis.readVInt();
//
//                long first = edis.readVLong();
//                long values = edis.readVLong();
//
//                List<BLinkNodeMetadata<Bytes>> nodes = new LinkedList<>();
//
//                int block;
//
//                while ((block = edis.readVInt()) != METADATA_PAGE_END_BLOCK) {
//
//                    if (block != METADATA_PAGE_NODE_BLOCK) {
//                        throw new IOException("Wrong block type " + block);
//                    }
//
//                    final boolean leaf = edis.readBoolean();
//
//                    long id = edis.readVLong();
//                    long storeId = edis.readVLong();
//
//                    boolean empty = edis.readBoolean();
//
//                    int keys = edis.readVInt();
//                    long bytes = edis.readVLong();
//
//                    long outlink = edis.readZLong();
//                    long rightlink = edis.readZLong();
//
//                    boolean hasInf = edis.readBoolean();
//
//                    Comparable<Bytes> rightsep;
//                    if (hasInf) {
//                        rightsep = EverBiggerKey.INSTANCE;
//                    } else {
//                        rightsep = Bytes.from_array(edis.readArray());
//                    }
//
//                    BLinkNodeMetadata<Bytes> node =
//                            new BLinkNodeMetadata<>(leaf, id, storeId, empty, keys, bytes, outlink, rightlink, rightsep);
//
//                    nodes.add(node);
//                }
//
//                return new BLinkMetadata<>(nextID, fast, fastheight, top, topheight, first, values, nodes);
//            }
//
//        }
//    }

    private final class BLinkIndexDataStorageImpl implements BLinkIndexDataStorage<Bytes,Long> {

        @Override
        public Map<Comparable<Bytes>,Long> loadNodePage(long pageId) throws IOException {
            return loadPage(pageId, INNER_NODE_PAGE);
        }

        @Override
        public Map<Comparable<Bytes>,Long> loadLeafPage(long pageId) throws IOException {
            return loadPage(pageId, LEAF_NODE_PAGE);
        }

        @SuppressWarnings("unchecked")
        @SuppressFBWarnings(value="DLS_DEAD_LOCAL_STORE", justification="flags still not used but it must be forcefully read")
        private Map<Comparable<Bytes>,Long> loadPage(long pageId, byte type) throws IOException {

            final byte[] data = dataStorageManager.readIndexPage(tableSpace, indexName, pageId);

            try ( ByteArrayInputStream bis = new ByteArrayInputStream(data);
                  ExtendedDataInputStream edis = new ExtendedDataInputStream(bis) ) {

                /* flags for future implementations, actually unused */
                @SuppressWarnings("unused")
                long flags = edis.readVLong();
                byte rtype = edis.readByte();

                if (rtype != type) {
                    throw new IOException("Wrong page type " + rtype + " expected " + type );
                }

                final Map<Comparable<Bytes>,Long> map = new HashMap<>();

                byte block;
                while((block = edis.readByte()) != NODE_PAGE_END_BLOCK) {

                    switch(block) {

                        case NODE_PAGE_KEY_VALUE_BLOCK:
                            map.put(Bytes.from_array(edis.readArray()),
                                    edis.readVLong());
                            break;

                        case NODE_PAGE_INF_BLOCK:
                            map.put(EverBiggerKey.INSTANCE, edis.readVLong());
                            break;

                        default:
                            throw new IOException("Wrong node block type " + block);

                    }
                }

                return map;
            }

        }

        @Override
        public long createNodePage(Map<Comparable<Bytes>,Long> data) throws IOException {
            /* Both node ids and leaf values are Long, direct both to a common method */
            return createPage(NEW_PAGE, data, INNER_NODE_PAGE);
        }

        @Override
        public long createLeafPage(Map<Comparable<Bytes>,Long> data) throws IOException {
            /* Both node ids and leaf values are Long, direct both to a common method */
            return createPage(NEW_PAGE, data, LEAF_NODE_PAGE);
        }


        @Override
        public void overwriteNodePage(long pageId, Map<Comparable<Bytes>,Long> data) throws IOException {
            /* Both node ids and leaf values are Long, direct both to a common method */
            createPage(pageId, data, INNER_NODE_PAGE);
        }

        @Override
        public void overwriteLeafPage(long pageId, Map<Comparable<Bytes>,Long> data) throws IOException {
            /* Both node ids and leaf values are Long, direct both to a common method */
            createPage(pageId, data, LEAF_NODE_PAGE);
        }


        private long createPage(long pageId, Map<Comparable<Bytes>,Long> data, byte type) throws IOException {

            final VisibleByteArrayOutputStream bos = new VisibleByteArrayOutputStream();
            try (ExtendedDataOutputStream edos = new ExtendedDataOutputStream(bos)) {

                /* flags for future implementations, actually unused */
                edos.writeVLong(0L);
                edos.writeByte(type);

                data.forEach((x, y) -> {
                    try {
                        if (x == EverBiggerKey.INSTANCE) {
                            // Handle special case for +inf key
                            edos.writeByte(NODE_PAGE_INF_BLOCK);
                            edos.writeVLong(y);
                        } else {
                            edos.writeByte(NODE_PAGE_KEY_VALUE_BLOCK);
                            edos.writeArray(((Bytes) x).to_array());
                            edos.writeVLong(y);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException("Unexpected IOException during node page write preparation", e);
                    }
                });

                edos.writeByte(NODE_PAGE_END_BLOCK);
            }

            /* Write/overwrite switch */
            if (pageId == NEW_PAGE) {
                pageId = newPageId.getAndIncrement();
            }

            dataStorageManager.writeIndexPage(tableSpace, indexName, pageId, bos.getBuffer(), 0, bos.size());

            return pageId;
        }
    }
}