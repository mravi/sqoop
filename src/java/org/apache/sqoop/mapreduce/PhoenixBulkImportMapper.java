package org.apache.sqoop.mapreduce;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.util.ColumnInfo;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.QueryUtil;
import org.apache.sqoop.phoenix.PhoenixConstants;
import org.apache.sqoop.phoenix.PhoenixUtil;

import com.cloudera.sqoop.lib.SqoopRecord;
import com.cloudera.sqoop.mapreduce.AutoProgressMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;

public class PhoenixBulkImportMapper 
		extends AutoProgressMapper<LongWritable, SqoopRecord,ImmutableBytesWritable ,KeyValue> {
	
	public static final Log LOG = LogFactory.getLog(
			PhoenixBulkImportMapper.class.getName());
	
	private Configuration conf;
	private List<ColumnInfo> columnInfos;
	private PhoenixConnection conn;
	private byte[] tableName;
	private PreparedStatement preparedStatement = null;
	
	/* holds the mapping of phoenix column to db column */
	private Map<String,String> columnMappings = null;
	
	@Override
	protected void setup(Mapper<LongWritable, SqoopRecord, ImmutableBytesWritable, KeyValue>.Context context)
			throws IOException, InterruptedException {
		conf = context.getConfiguration();
		try {
			conn = (PhoenixConnection) QueryUtil.getConnection(conf);
			conn.setAutoCommit(false);
			String phoenixTable = PhoenixConfigurationUtil.getOutputTableName(conf);
			tableName = Bytes.toBytes(phoenixTable);
            columnInfos = PhoenixConfigurationUtil.getUpsertColumnMetadataList(conf);
            String upsertSql = QueryUtil.constructUpsertStatement(phoenixTable, columnInfos);
            preparedStatement = conn.prepareStatement(upsertSql);
            
            String columnMaps = conf.get(PhoenixConstants.PHOENIX_COLUMN_MAPPING);
            for(Map.Entry<String,String> prop : conf) {
				System.out.println(String.format(" the key is [%s] and the value is [%s]" , prop.getKey(),prop.getValue()));
			}
            columnMappings = PhoenixUtil.getPhoenixToSqoopMap(columnMaps);
            
		} catch (Exception e) {
			 throw new RuntimeException("Failed to setup due to ." + e.getMessage());
		}
	}

	
	
	@Override
	protected void map(LongWritable key, SqoopRecord value,
			Mapper<LongWritable, SqoopRecord, ImmutableBytesWritable, KeyValue>.Context context)
					throws IOException, InterruptedException {
		
		try {
			ImmutableBytesWritable outputKey = new ImmutableBytesWritable();
			Map<String,Object> fields = value.getFieldMap();
			int i = 1;
			for(ColumnInfo colInfo : columnInfos) {
				String pColName = colInfo.getDisplayName();
				String sColName = columnMappings.get(pColName);
				Object sColValue = fields.get(sColName);
				preparedStatement.setObject(i++, sColValue);
			}
			preparedStatement.execute();
		
			Iterator<Pair<byte[], List<KeyValue>>> uncommittedDataIterator = PhoenixRuntime.getUncommittedDataIterator(conn, true);
			while (uncommittedDataIterator.hasNext()) {
			    Pair<byte[], List<KeyValue>> kvPair = uncommittedDataIterator.next();
			    if (Bytes.compareTo(tableName, kvPair.getFirst()) != 0) {
			    	// skip edits for other tables
			    	continue;
			    }
			    List<KeyValue> keyValueList = kvPair.getSecond();
			    for (KeyValue kv : keyValueList) {
			        outputKey.set(kv.getBuffer(), kv.getRowOffset(), kv.getRowLength());
			        context.write(outputKey, kv);
			    }
			}
			conn.rollback();
			
		} catch (SQLException e) {
			throw new RuntimeException("Failed to process the record in the mapper due to " + e.getMessage());
		} 
	}
	
	@Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
