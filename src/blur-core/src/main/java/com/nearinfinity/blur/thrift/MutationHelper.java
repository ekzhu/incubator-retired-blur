package com.nearinfinity.blur.thrift;

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
import java.util.List;

import org.apache.hadoop.io.BytesWritable;

import com.nearinfinity.blur.manager.BlurPartitioner;
import com.nearinfinity.blur.thrift.generated.Record;
import com.nearinfinity.blur.thrift.generated.RecordMutation;
import com.nearinfinity.blur.thrift.generated.Row;
import com.nearinfinity.blur.thrift.generated.RowMutation;
import com.nearinfinity.blur.utils.BlurConstants;
import com.nearinfinity.blur.utils.BlurUtil;

public class MutationHelper {

  public static String getShardName(String table, String rowId, int numberOfShards, BlurPartitioner<BytesWritable, ?> blurPartitioner) {
    BytesWritable key = getKey(rowId);
    int partition = blurPartitioner.getPartition(key, null, numberOfShards);
    return BlurUtil.getShardName(BlurConstants.SHARD_PREFIX, partition);
  }

  public static void validateMutation(RowMutation mutation) {
    if (mutation == null) {
      throw new NullPointerException("Mutation can not be null.");
    }
    if (mutation.rowId == null) {
      throw new NullPointerException("Rowid can not be null in mutation.");
    }
    if (mutation.table == null) {
      throw new NullPointerException("Table can not be null in mutation.");
    }
  }

  public static BytesWritable getKey(String rowId) {
    return new BytesWritable(rowId.getBytes());
  }

  public static Row getRowFromMutations(String id, List<RecordMutation> recordMutations) {
    Row row = new Row().setId(id);
    for (RecordMutation mutation : recordMutations) {
      Record record = mutation.getRecord();
      switch (mutation.recordMutationType) {
      case REPLACE_ENTIRE_RECORD:
        row.addToRecords(record);
        break;
      default:
        throw new RuntimeException("Not supported [" + mutation.recordMutationType + "]");
      }
    }
    return row;
  }
}
