package com.gkgd.cleantransport.test

import java.text.SimpleDateFormat
import java.util.Date

import com.gkgd.cleantransport.entity.ods.TblPosinfo
import com.gkgd.cleantransport.jt8080.{HexStringUtils, LocationInformationReport, MsgDecoderUtil, PackageData}

/**
  * @ModelName
  * @Description
  * @Author zhangjinhang
  * @Date 2021/1/7 11:23
  * @Version V1.0.0
  */
object Test {

  def main(args: Array[String]): Unit = {
    val record = "7e07040049013007252658800400010100440000000000000001ffffffffffffffff0000000000002101240010440104655209730202000003020000040200002504614d09402a0200002b0400000000300105310100be7e"
    val input: Array[Byte] = HexStringUtils.chars2Bytes(record.toCharArray)
    val packageData: PackageData = new MsgDecoderUtil().bytes2PackageData(input)
    val header: PackageData.MsgHeader = packageData.getMsgHeader
    val locationInformationReport: LocationInformationReport = LocationInformationReport.getEntity(packageData.getMsgBodyBytes)
    val tblPosinfo = new TblPosinfo(header.getTerminalPhone, locationInformationReport)
    val sdf = new SimpleDateFormat("yyyy-MM-dd")
    val nowDate: String = sdf.format(new Date)
    println(nowDate == tblPosinfo.time.split(" ")(0))
    println(tblPosinfo.time)
  }

}
