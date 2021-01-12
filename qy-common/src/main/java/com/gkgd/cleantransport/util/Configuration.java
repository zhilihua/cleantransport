package com.gkgd.cleantransport.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class Configuration {
    public static Properties conf(String name) {
        Properties props = new Properties();
        String path = null;
        try {
            path = new File("").getCanonicalPath();
            File file = new File(path + "/conf");
            if(!file.exists()){
                //文件不存在，加载自带文件
                props.load(Configuration.class.getClassLoader().getResourceAsStream(name));
            }else {
                //加载配置文件
                props.load(new FileInputStream(new File(path+"/conf/"+name)));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return props;
    }

    public static void main(String[] args) {
        Properties conf = Configuration.conf("config.properties");
        System.out.println(conf.getProperty("mysql.host"));
    }
}
