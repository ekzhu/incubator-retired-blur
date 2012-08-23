package com.nearinfinity.agent.collectors;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants.DatanodeReportType;
import org.springframework.jdbc.core.JdbcTemplate;

public class HDFSCollector {
  private static final Log log = LogFactory.getLog(HDFSCollector.class);
  
  public static void startCollecting(final String uriString, final String name, final String user, final JdbcTemplate jdbc) {
    try {
      new Thread(new Runnable(){
        @Override
        public void run() {
          try {           
            URI uri = new URI(uriString);
            String host = uri.getHost();
            String port = String.valueOf(uri.getPort());
            
            int hdfsId = jdbc.queryForInt("select id from hdfs where name = ?", name);
            
            FileSystem fileSystem = (user != null) ? FileSystem.get(uri, new Configuration(), user) : FileSystem.get(uri, new Configuration());
  
            if (fileSystem instanceof DistributedFileSystem) {
              DistributedFileSystem dfs = (DistributedFileSystem) fileSystem;
  
              FsStatus ds = dfs.getStatus();
              long capacity = ds.getCapacity();
              long used = ds.getUsed();
              long logical_used = used / dfs.getDefaultReplication();
              long remaining = ds.getRemaining();
              long presentCapacity = used + remaining;
  
              long liveNodes = -1;
              long deadNodes = -1;
              long totalNodes = -1;
              
              try {
                DatanodeInfo[] live = dfs.getClient().datanodeReport(DatanodeReportType.LIVE);
                DatanodeInfo[] dead = dfs.getClient().datanodeReport(DatanodeReportType.DEAD);
                
                liveNodes = live.length;
                deadNodes = dead.length;
                totalNodes = liveNodes + deadNodes;
              } catch (Exception e) {
                log.warn("Access denied for user. Skipping node information.");
              }
              
              Calendar cal = Calendar.getInstance();
              TimeZone z = cal.getTimeZone();
              cal.add(Calendar.MILLISECOND, -(z.getOffset(cal.getTimeInMillis())));
              jdbc.update("insert into hdfs_stats (config_capacity, present_capacity, dfs_remaining, dfs_used_real, dfs_used_logical, dfs_used_percent, under_replicated, corrupt_blocks, missing_blocks, total_nodes, live_nodes, dead_nodes, created_at, host, port, hdfs_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", 
                    capacity,
                    presentCapacity,
                    remaining,
                    used,
                    logical_used,
                    (((1.0 * used) / presentCapacity) * 100),
                    dfs.getUnderReplicatedBlocksCount(),
                    dfs.getCorruptBlocksCount(),
                    dfs.getMissingBlocksCount(),
                    totalNodes,
                    liveNodes,
                    deadNodes,
                    cal.getTime(),
                    host,
                    port,
                    hdfsId);
            }
          } catch (Exception e) {
            log.debug(e);
          }
        }
      }).start();
    } catch (Exception e) {
      log.debug(e);
    }
  }
  
  public static void initializeHdfs(String name, String uriString, JdbcTemplate jdbc) {
    List<Map<String, Object>> existingHdfs = jdbc.queryForList("select id from hdfs where name=?", name);
    
    if (existingHdfs.isEmpty()) {
      URI uri;
      try {
        uri = new URI(uriString);
        jdbc.update("insert into hdfs (name, host, port) values (?, ?, ?)", name, uri.getHost(), uri.getPort());
      } catch (URISyntaxException e) {
        log.debug(e);
      }
    } else {
      URI uri;
      try {
        uri = new URI(uriString);
        jdbc.update("update hdfs set host=?, port=? where id=?", uri.getHost(), uri.getPort(), existingHdfs.get(0).get("ID"));
      } catch (URISyntaxException e) {
        log.debug(e);
      }
    }
  }
  
  public static void cleanStats(JdbcTemplate jdbc) {
    Calendar now = Calendar.getInstance();
    TimeZone z = now.getTimeZone();
    now.add(Calendar.MILLISECOND, -(z.getOffset(new Date().getTime())));
    
    Calendar oneWeekAgo = Calendar.getInstance();
    oneWeekAgo.setTimeInMillis(now.getTimeInMillis());
    oneWeekAgo.add(Calendar.DATE, -7);
    
    jdbc.update("delete from hdfs_stats where created_at < ?", oneWeekAgo.getTime());
  }
}
