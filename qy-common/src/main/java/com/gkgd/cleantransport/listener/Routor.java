package com.gkgd.cleantransport.listener;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import java.util.HashMap;

public class Routor {
    private String connectString="192.168.10.21:2181";
    private int sessionTimeout=6000;
    private ZooKeeper zooKeeper;

    private String basePath="/MyDB001";
    private HashMap<String, String> changeTable=new HashMap<>();

    //初始化客户端对象
    public void init() throws Exception{
        zooKeeper = new ZooKeeper(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent watchedEvent) {

            }
        });
    }

    //获取当前启动的Server进程有那些，获取Server进程的信息
    public void getData (String path) throws KeeperException, InterruptedException {

        byte[] info = zooKeeper.getData(basePath + "/" + path, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                try {
                    getData(path);
                } catch (KeeperException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, null);

        System.out.println(new String(info));

    }

    public void doOtherBusiness() throws Exception {
        //不让进程结束
        while (true) {
            Thread.sleep(10000);
        }

    }

    public static void main(String[] args) throws Exception {
        Routor routor = new Routor();
        //初始化客户端
        routor.init();
        //获取数据
        routor.getData("sys_user");
        routor.doOtherBusiness();
    }
}
