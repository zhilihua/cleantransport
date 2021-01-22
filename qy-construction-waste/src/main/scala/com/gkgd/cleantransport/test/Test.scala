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
    val record = "0200006b015700611399009200000000000010010207a94406c81e4800000000000014100108243201040000040002020000030200001404000000001504000000001604000000001702000018030000002a0200002b040000000030011c310100e0140104000000000202000003080000000000000000c1"
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
