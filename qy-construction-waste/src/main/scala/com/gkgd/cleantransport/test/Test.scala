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
    val record = "7e0200006b01570061138400110000000000001001020768cf06c852330000003a00a71410010803020104000000010202\n0000030200001404000000001504000000001604000000001702000018030000002a0200002b040000000030011f310100e0140104000000000202000003080000000000000000ae7e\n03020000040200002504000000002a0200002b0400000000300105310109317e\n03020000040200002504000000002a0200002b0400000000300105310109ca7e"
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
