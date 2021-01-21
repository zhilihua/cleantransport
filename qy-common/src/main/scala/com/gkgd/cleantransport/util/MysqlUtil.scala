package com.gkgd.cleantransport.util

import java.sql.{ResultSet, ResultSetMetaData, Statement}

import com.alibaba.fastjson.JSONObject

import scala.collection.mutable.ListBuffer

object MysqlUtil {
    def queryList(sql: String): List[JSONObject] = {
        val resultList: ListBuffer[JSONObject] = new ListBuffer[JSONObject]()
        val conn = duridUtils.getConn
        val stat: Statement = conn.createStatement
        val rs: ResultSet = stat.executeQuery(sql)
        val md: ResultSetMetaData = rs.getMetaData
        while (rs.next) {
            val rowData = new JSONObject()
            for (i <- 1 to md.getColumnCount) {
                rowData.put(md.getColumnName(i), rs.getObject(i))
            }
            resultList += rowData
        }

        stat.close()
        conn.close()
        resultList.toList
    }

}
