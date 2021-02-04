package com.gkgd.cleantransport.listener;

import com.alibaba.fastjson.JSON;
import com.gkgd.cleantransport.util.Configuration;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

public class Server {
    //mysql配置变量
    private Properties propsMysql  = Configuration.conf("mysql-conf.properties");
    private String host  = propsMysql.getProperty("mysql.host");
    private String port = propsMysql.getProperty("mysql.port");
    private String user = propsMysql.getProperty("mysql.user");
    private String passwd = propsMysql.getProperty("mysql.passwd");
    private String db = propsMysql.getProperty("mysql.db");

    //zookeeper配置变量
    private String connectString;
    private int sessionTimeout=6000;
    private ZooKeeper zooKeeper;
    private String basePath="/MyDB001";

    private Properties propsZookeeper  = Configuration.conf("zookeeper-conf.properties");
    private String tables = propsZookeeper.getProperty("tables");

    //初始化客户端对象
    public void init() throws Exception {
        connectString = propsZookeeper.getProperty("zk.servers");
        zooKeeper = new ZooKeeper(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent watchedEvent) {

            }
        });

        //如果父节点不存在则创建
        if(!ifNodeExists(basePath)) {
            zooKeeper.create(basePath, "info".getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    //使用zk客户端注册临时节点
    public void register() throws Exception {
        String info = LocalDateTime.now().toString();
        //节点必须是临时带序号的节点
        zooKeeper.create(basePath+"/"+tables, info.getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    }

    //其他的业务功能
    public void doOtherBusiness(String table, String sql) throws Exception {
        //创建mysql连接
        Class.forName("com.mysql.jdbc.Driver");
        Connection conn = DriverManager.getConnection(
                "jdbc:mysql://" + host + ":" + port + "/" + db + "?characterEncoding=utf-8&useSSL=false", user, passwd);
        Statement stat = conn.createStatement();

        //反复查询是否变化
        while (true) {
            ResultSet rs = stat.executeQuery(sql);
            while (rs.next()){
                System.out.println(rs.getInt(1));
            }
            Thread.sleep(10000);
            System.out.println(table);
            set("sys_user", LocalDateTime.now().toString());
        }

    }

    //向节点中写入数据
    public void set(String NodeName, String info) throws KeeperException, InterruptedException {
        zooKeeper.setData(basePath +"/"+NodeName, info.getBytes(), -1);
    }

    //判断当前节点是否存在
    public boolean ifNodeExists(String NodeName) throws Exception {

        Stat stat = zooKeeper.exists(NodeName, false);

        return stat != null;

    }

    //主方法
    public static void main(String[] args) throws Exception {
        String s = "['aaa', 'bbb']";
        List<String> strings = JSON.parseArray(s, String.class);
        System.out.println(strings.get(0));
        System.out.println(strings.get(1));
//        Server server = new Server();
//        //初始化对象
//        server.init();
//        //注册节点
//        server.register();
//        //业务部分(注册表)
//        String sys_user_sql = "select * from sys_user";
//        server.doOtherBusiness("sys_user", sys_user_sql);
    }

}
