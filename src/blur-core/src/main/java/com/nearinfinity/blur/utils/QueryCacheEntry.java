package com.nearinfinity.blur.utils;

import com.nearinfinity.blur.thrift.generated.BlurQuery;
import com.nearinfinity.blur.thrift.generated.BlurResults;

public class QueryCacheEntry {
  public BlurResults results;
  public long timestamp;

  public QueryCacheEntry(BlurResults cacheResults) {
    results = cacheResults;
    timestamp = System.currentTimeMillis();
  }

  public BlurResults getBlurResults(BlurQuery blurQuery) {
    BlurResults blurResults = new BlurResults(results);
    blurResults.query = blurQuery;
    return blurResults;
  }
}