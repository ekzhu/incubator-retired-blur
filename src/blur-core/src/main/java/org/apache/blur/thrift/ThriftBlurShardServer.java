package org.apache.blur.thrift;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import static org.apache.blur.utils.BlurConstants.BLUR_CLUSTER;
import static org.apache.blur.utils.BlurConstants.BLUR_CLUSTER_NAME;
import static org.apache.blur.utils.BlurConstants.BLUR_CONTROLLER_BIND_PORT;
import static org.apache.blur.utils.BlurConstants.BLUR_GUI_CONTROLLER_PORT;
import static org.apache.blur.utils.BlurConstants.BLUR_GUI_SHARD_PORT;
import static org.apache.blur.utils.BlurConstants.BLUR_INDEXMANAGER_SEARCH_THREAD_COUNT;
import static org.apache.blur.utils.BlurConstants.BLUR_MAX_CLAUSE_COUNT;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_BIND_ADDRESS;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_BIND_PORT;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_BLOCKCACHE_DIRECT_MEMORY_ALLOCATION;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_BLOCKCACHE_SLAB_COUNT;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_FILTER_CACHE_CLASS;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_HOSTNAME;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_INDEX_WARMUP_CLASS;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_OPENER_THREAD_COUNT;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_SAFEMODEDELAY;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_SERVER_THRIFT_THREAD_COUNT;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_TIME_BETWEEN_COMMITS;
import static org.apache.blur.utils.BlurConstants.BLUR_SHARD_TIME_BETWEEN_REFRESHS;
import static org.apache.blur.utils.BlurConstants.BLUR_ZOOKEEPER_CONNECTION;
import static org.apache.blur.utils.BlurConstants.BLUR_ZOOKEEPER_SYSTEM_TIME_TOLERANCE;
import static org.apache.blur.utils.BlurUtil.quietClose;

import org.apache.blur.BlurConfiguration;
import org.apache.blur.concurrent.SimpleUncaughtExceptionHandler;
import org.apache.blur.concurrent.ThreadWatcher;
import org.apache.blur.gui.HttpJettyServer;
import org.apache.blur.log.Log;
import org.apache.blur.log.LogFactory;
import org.apache.blur.manager.BlurFilterCache;
import org.apache.blur.manager.BlurQueryChecker;
import org.apache.blur.manager.DefaultBlurFilterCache;
import org.apache.blur.manager.IndexManager;
import org.apache.blur.manager.clusterstatus.ZookeeperClusterStatus;
import org.apache.blur.manager.indexserver.BlurIndexWarmup;
import org.apache.blur.manager.indexserver.BlurServerShutDown;
import org.apache.blur.manager.indexserver.BlurServerShutDown.BlurShutdown;
import org.apache.blur.manager.indexserver.DefaultBlurIndexWarmup;
import org.apache.blur.manager.indexserver.DistributedIndexServer;
import org.apache.blur.manager.writer.BlurIndexRefresher;
import org.apache.blur.metrics.BlurMetrics;
import org.apache.blur.store.blockcache.BlockCache;
import org.apache.blur.store.blockcache.BlockDirectory;
import org.apache.blur.store.blockcache.BlockDirectoryCache;
import org.apache.blur.store.blockcache.Cache;
import org.apache.blur.store.buffer.BufferStore;
import org.apache.blur.thrift.generated.Blur.Iface;
import org.apache.blur.utils.BlurUtil;
import org.apache.blur.zookeeper.ZkUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.lucene.index.IndexDeletionPolicy;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooKeeper;


public class ThriftBlurShardServer extends ThriftServer {

  private static final Log LOG = LogFactory.getLog(ThriftBlurShardServer.class);

  public static void main(String[] args) throws Exception {
    int serverIndex = getServerIndex(args);
    LOG.info("Setting up Shard Server");

    Thread.setDefaultUncaughtExceptionHandler(new SimpleUncaughtExceptionHandler());
    BlurConfiguration configuration = new BlurConfiguration();

    ThriftServer server = createServer(serverIndex, configuration);
    server.start();
  }

  public static ThriftServer createServer(int serverIndex, BlurConfiguration configuration) throws Exception {
    // setup block cache
    // 134,217,728 is the slab size, therefore there are 16,384 blocks
    // in a slab when using a block size of 8,192
    int numberOfBlocksPerSlab = 16384;
    int blockSize = BlockDirectory.BLOCK_SIZE;
    int slabCount = configuration.getInt(BLUR_SHARD_BLOCKCACHE_SLAB_COUNT, 1);
    Cache cache;
    Configuration config = new Configuration();
    
    String bindAddress = configuration.get(BLUR_SHARD_BIND_ADDRESS);
    int bindPort = configuration.getInt(BLUR_SHARD_BIND_PORT, -1);
    bindPort += serverIndex;
    
    BlurMetrics blurMetrics = new BlurMetrics(config);
    
    int baseGuiPort = Integer.parseInt(configuration.get(BLUR_GUI_SHARD_PORT));
    final HttpJettyServer httpServer;
    if (baseGuiPort > 0) {
      int webServerPort = baseGuiPort + serverIndex;

      // TODO: this got ugly, there has to be a better way to handle all these
      // params
      // without reversing the mvn dependancy and making blur-gui on top.
      httpServer = new HttpJettyServer(bindPort, webServerPort, configuration.getInt(BLUR_CONTROLLER_BIND_PORT, -1), configuration.getInt(BLUR_SHARD_BIND_PORT, -1),
          configuration.getInt(BLUR_GUI_CONTROLLER_PORT, -1), configuration.getInt(BLUR_GUI_SHARD_PORT, -1), "shard", blurMetrics);
    } else {
      httpServer = null;
    }
    
    
    if (slabCount >= 1) {
      BlockCache blockCache;
      boolean directAllocation = configuration.getBoolean(BLUR_SHARD_BLOCKCACHE_DIRECT_MEMORY_ALLOCATION, true);

      int slabSize = numberOfBlocksPerSlab * blockSize;
      LOG.info("Number of slabs of block cache [{0}] with direct memory allocation set to [{1}]", slabCount, directAllocation);
      LOG.info("Block cache target memory usage, slab size of [{0}] will allocate [{1}] slabs and use ~[{2}] bytes", slabSize, slabCount, ((long) slabCount * (long) slabSize));

      int _1024Size = configuration.getInt("blur.shard.buffercache.1024", 8192);
      int _8192Size = configuration.getInt("blur.shard.buffercache.8192", 8192);
      BufferStore.init(_1024Size, _8192Size);
      
      try {
        long totalMemory = (long) slabCount * (long) numberOfBlocksPerSlab * (long) blockSize;
        blockCache = new BlockCache(directAllocation, totalMemory, slabSize);
      } catch (OutOfMemoryError e) {
        if ("Direct buffer memory".equals(e.getMessage())) {
          System.err
              .println("The max direct memory is too low.  Either increase by setting (-XX:MaxDirectMemorySize=<size>g -XX:+UseLargePages) or disable direct allocation by (blur.shard.blockcache.direct.memory.allocation=false) in blur-site.properties");
          System.exit(1);
        }
        throw e;
      }
      cache = new BlockDirectoryCache(blockCache);
    } else {
      cache = BlockDirectory.NO_CACHE;
    }


    LOG.info("Shard Server using index [{0}] bind address [{1}]", serverIndex, bindAddress + ":" + bindPort);

    String nodeNameHostName = getNodeName(configuration, BLUR_SHARD_HOSTNAME);
    String nodeName = nodeNameHostName + ":" + bindPort;
    String zkConnectionStr = isEmpty(configuration.get(BLUR_ZOOKEEPER_CONNECTION), BLUR_ZOOKEEPER_CONNECTION);

    BlurQueryChecker queryChecker = new BlurQueryChecker(configuration);

    final ZooKeeper zooKeeper = ZkUtils.newZooKeeper(zkConnectionStr);
    try {
      ZookeeperSystemTime.checkSystemTime(zooKeeper, configuration.getLong(BLUR_ZOOKEEPER_SYSTEM_TIME_TOLERANCE, 3000));
    } catch (KeeperException e) {
      if (e.code() == Code.CONNECTIONLOSS) {
        System.err.println("Cannot connect zookeeper to [" + zkConnectionStr + "]");
        System.exit(1);
      }
    }

    BlurUtil.setupZookeeper(zooKeeper, configuration.get(BLUR_CLUSTER_NAME));

    final ZookeeperClusterStatus clusterStatus = new ZookeeperClusterStatus(zooKeeper);

    final BlurIndexRefresher refresher = new BlurIndexRefresher();
    refresher.init();

    BlurFilterCache filterCache = getFilterCache(configuration);
    BlurIndexWarmup indexWarmup = getIndexWarmup(configuration);
    IndexDeletionPolicy indexDeletionPolicy = new KeepOnlyLastCommitDeletionPolicy();

    final DistributedIndexServer indexServer = new DistributedIndexServer();
    indexServer.setBlurMetrics(blurMetrics);
    indexServer.setCache(cache);
    indexServer.setClusterStatus(clusterStatus);
    indexServer.setClusterName(configuration.get(BLUR_CLUSTER_NAME, BLUR_CLUSTER));
    indexServer.setConfiguration(config);
    indexServer.setNodeName(nodeName);
    indexServer.setRefresher(refresher);
    indexServer.setShardOpenerThreadCount(configuration.getInt(BLUR_SHARD_OPENER_THREAD_COUNT, 16));
    indexServer.setZookeeper(zooKeeper);
    indexServer.setFilterCache(filterCache);
    indexServer.setSafeModeDelay(configuration.getLong(BLUR_SHARD_SAFEMODEDELAY, 60000));
    indexServer.setWarmup(indexWarmup);
    indexServer.setIndexDeletionPolicy(indexDeletionPolicy);
    indexServer.setTimeBetweenCommits(configuration.getLong(BLUR_SHARD_TIME_BETWEEN_COMMITS, 60000));
    indexServer.setTimeBetweenRefreshs(configuration.getLong(BLUR_SHARD_TIME_BETWEEN_REFRESHS, 500));
    indexServer.init();

    final IndexManager indexManager = new IndexManager();
    indexManager.setIndexServer(indexServer);
    indexManager.setMaxClauseCount(configuration.getInt(BLUR_MAX_CLAUSE_COUNT, 1024));
    indexManager.setThreadCount(configuration.getInt(BLUR_INDEXMANAGER_SEARCH_THREAD_COUNT, 32));
    indexManager.setBlurMetrics(blurMetrics);
    indexManager.setFilterCache(filterCache);
    indexManager.init();

    final BlurShardServer shardServer = new BlurShardServer();
    shardServer.setIndexServer(indexServer);
    shardServer.setIndexManager(indexManager);
    shardServer.setZookeeper(zooKeeper);
    shardServer.setClusterStatus(clusterStatus);
    shardServer.setQueryChecker(queryChecker);
    shardServer.setConfiguration(configuration);
    shardServer.init();

    Iface iface = BlurUtil.recordMethodCallsAndAverageTimes(blurMetrics, shardServer, Iface.class);

    int threadCount = configuration.getInt(BLUR_SHARD_SERVER_THRIFT_THREAD_COUNT, 32);

    final ThriftBlurShardServer server = new ThriftBlurShardServer();
    server.setNodeName(nodeName);
    server.setBindAddress(bindAddress);
    server.setBindPort(bindPort);
    server.setThreadCount(threadCount);
    server.setIface(iface);
    server.setConfiguration(configuration);


    // This will shutdown the server when the correct path is set in zk
    BlurShutdown shutdown = new BlurShutdown() {
      @Override
      public void shutdown() {
        ThreadWatcher threadWatcher = ThreadWatcher.instance();
        quietClose(refresher, server, shardServer, indexManager, indexServer, threadWatcher, clusterStatus, zooKeeper, httpServer);
      }
    };
    server.setShutdown(shutdown);
    new BlurServerShutDown().register(shutdown, zooKeeper);
    return server;
  }

  private static BlurFilterCache getFilterCache(BlurConfiguration configuration) {
    String _blurFilterCacheClass = configuration.get(BLUR_SHARD_FILTER_CACHE_CLASS);
    if (_blurFilterCacheClass != null) {
      try {
        Class<?> clazz = Class.forName(_blurFilterCacheClass);
        return (BlurFilterCache) clazz.newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return new DefaultBlurFilterCache();
  }

  private static BlurIndexWarmup getIndexWarmup(BlurConfiguration configuration) {
    String _blurFilterCacheClass = configuration.get(BLUR_SHARD_INDEX_WARMUP_CLASS);
    if (_blurFilterCacheClass != null) {
      try {
        Class<?> clazz = Class.forName(_blurFilterCacheClass);
        return (BlurIndexWarmup) clazz.newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return new DefaultBlurIndexWarmup();
  }
}
