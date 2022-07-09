package com.alibaba.otter;
 
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.common.utils.AddressUtils;
import com.alibaba.otter.canal.protocol.CanalEntry.*;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
 
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
 
@Component
public class CanalClient {

  private Logger logger = LoggerFactory.getLogger(CanalClient.class);

  //sql队列
  private Queue<String> SQL_QUEUE = new ConcurrentLinkedQueue<>();
 
  @Resource
  private DataSource dataSource;
 
  /**
   * canal入库方法
   */
  public void run() {

    // 创建 gi链接
    CanalConnector connector = CanalConnectors.newSingleConnector(new InetSocketAddress(AddressUtils.getHostIp(),
            11111), "example", "", "");
    int batchSize = 1000;
    try {
      connector.connect();
      connector.subscribe(".*\\..*");

      connector.rollback();
      try {
        while (true) {
          //尝试从master那边拉去数据batchSize条记录，有多少取多少
          Message message = connector.getWithoutAck(batchSize);
          long batchId = message.getId();
          int size = message.getEntries().size();
          if (batchId == -1 || size == 0) {
            Thread.sleep(1000);
          } else {
            dataHandle(message.getEntries());
          }
          connector.ack(batchId);
 
          //当队列里面堆积的sql大于一定数值的时候就模拟执行
          if (SQL_QUEUE.size() >= 1) {
            executeQueueSql();
          }
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      } catch (InvalidProtocolBufferException e) {
        e.printStackTrace();
      }
    } finally {
      logger.error("连接失败");
      connector.disconnect();
    }
  }
 
  /**
   * 模拟执行队列里面的sql语句
   */
  public void executeQueueSql() {
    int size = SQL_QUEUE.size();
    for (int i = 0; i < size; i++) {
      String sql = SQL_QUEUE.poll();
      System.out.println("[sql]----> " + sql);
//     String sql_bak =
      this.execute(sql.toString());
    }
  }
 
  /**
   * 数据处理
   *
   * @param entrys
   */
  private void dataHandle(List<Entry> entrys) throws InvalidProtocolBufferException {
    for (Entry entry : entrys) {
      if (EntryType.ROWDATA == entry.getEntryType()) {
        RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
        EventType eventType = rowChange.getEventType();
        if (eventType == EventType.DELETE) {
          saveDeleteSql(entry);
          getdelBeforSql(entry);
        }
        else if (eventType == EventType.UPDATE) {
          saveUpdateSql(entry);
        } else if (eventType == EventType.INSERT) {
          saveInsertSql(entry);
        }
      }
    }
  }
 
  /**
   * 保存更新语句
   *
   * @param entry
   */
  private void saveUpdateSql(Entry entry) {
    try {
      RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
      List<RowData> rowDatasList = rowChange.getRowDatasList();
      for (RowData rowData : rowDatasList) {
        List<Column> newColumnList = rowData.getAfterColumnsList();
        StringBuffer sql = new StringBuffer("update " + entry.getHeader().getTableName() + " set ");
        for (int i = 0; i < newColumnList.size(); i++) {
          sql.append(" " + newColumnList.get(i).getName()
            + " = '" + newColumnList.get(i).getValue() + "'");
          if (i != newColumnList.size() - 1) {
            sql.append(",");
          }
        }
        sql.append(" where ");
        List<Column> oldColumnList = rowData.getBeforeColumnsList();
        for (Column column : oldColumnList) {
          if (column.getIsKey()) {
            //暂时只支持单一主键
            sql.append(column.getName() + "=" + column.getValue());
            break;
          }
        }
        logger.info("[canal adapter 接收到MQ消息: "+EventType.UPDATE+"]" + sql.toString());

//        SQL_QUEUE.add(sql.toString());
      }
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }
  }
 
  /**
   * 保存删除语句
   *
   * @param entry
   */
  private void saveDeleteSql(Entry entry) {
    try {
      RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
      List<RowData> rowDatasList = rowChange.getRowDatasList();
      for (RowData rowData : rowDatasList) {
        List<Column> columnList = rowData.getBeforeColumnsList();
        StringBuffer sql = new StringBuffer("delete from " + entry.getHeader().getTableName() + " where ");
        for (Column column : columnList) {
          if (column.getIsKey()) {
            //暂时只支持单一主键
            sql.append(column.getName() + "=" + column.getValue());
            break;
          }
        }
        logger.info("[canal adapter 接收到MQ消息: "+EventType.INSERT+"]" + sql.toString());

//        SQL_QUEUE.add(sql.toString());
      }
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }
  }
 
  /**
   * 保存插入语句
   *
   * @param entry
   */
  private void saveInsertSql(Entry entry) {
    try {
      RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
      List<RowData> rowDatasList = rowChange.getRowDatasList();
      for (RowData rowData : rowDatasList) {
        List<Column> columnList = rowData.getAfterColumnsList();
        StringBuffer sql = new StringBuffer("insert into " + entry.getHeader().getTableName() + " (");
        for (int i = 0; i < columnList.size(); i++) {
          sql.append(columnList.get(i).getName());
          if (i != columnList.size() - 1) {
            sql.append(",");
          }
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columnList.size(); i++) {
          sql.append("'" + columnList.get(i).getValue() + "'");
          if (i != columnList.size() - 1) {
            sql.append(",");
          }
        }
        sql.append(")");
        logger.info("canal adapter 接收到MQ消息: "+EventType.DELETE+"===srart====" + sql.toString()+"===end====");

//        SQL_QUEUE.add(sql.toString());
      }
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }
  }



  /**
   * 获取删除前的数据，写入文件（误删数据重binlog恢复数据使用）
   *
   * @param entry
   */
  private void getdelBeforSql(Entry entry) {
    try {
      RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
      List<RowData> rowDatasList = rowChange.getRowDatasList();
      for (RowData rowData : rowDatasList) {
        List<Column> columnList = rowData.getBeforeColumnsList();
        StringBuffer sql = new StringBuffer("insert into " + entry.getHeader().getTableName() + " (");
        for (int i = 0; i < columnList.size(); i++) {
          sql.append(columnList.get(i).getName());
          if (i != columnList.size() - 1) {
            sql.append(",");
          }
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columnList.size(); i++) {
          sql.append("'" + columnList.get(i).getValue() + "'");
          if (i != columnList.size() - 1) {
            sql.append(",");
          }
        }
        sql.append(");");
        logger.info("canal adapter 接收到消息: "+EventType.DELETE+"{[" + sql.toString()+"]}");
        String fileName = "/Users/wbq/SoftWare/canal/canal-tcp/canal-adapter/canal.sample/logs/sql.txt";
        method2(fileName,sql.toString());
//        SQL_QUEUE.add(sql.toString());
      }
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }
  }

  public  void method2(String file, String conent) {
    BufferedWriter out = null;
    try {
      out = new BufferedWriter(new OutputStreamWriter(
              new FileOutputStream(file, true)));
      out.write(conent+"\r\n");
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        out.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
  /**
   * 入库
   * @param sql
   */
  public void execute(String sql) {
    Connection con = null;
    try {
      if(null == sql) return;
      con = dataSource.getConnection();
      QueryRunner qr = new QueryRunner();
      int row = qr.execute(con, sql);
      System.out.println("update: "+ row);
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      DbUtils.closeQuietly(con);
    }
  }
}
 