package com.gkgd.cleantransport.entity

case class AlarmBeanScala(
                //===========================告警信息=========================
                //围栏信息
                  fence_id: String,
                  fence_name: String,
                  fence_area_id: String,
                  fence_flag: Int,

                //违规地点儿
                 alarm_start_time: String,
                 alarm_start_lng: Double,
                 alarm_start_lat: Double,
                 alarm_start_address: String,
                 alarm_end_time: String,
                 alarm_end_lng: Double,
                 alarm_end_lat: Double,
                 alarm_end_address: String,

                //违规代码
                 illegal_type_code: String,

                //扣分信息
                 illegal_type_short_desc: String,
                 illegal_type_desc: String,
                 enterprise_score: Double,
                 vehicle_score: Double,
                 driver_score: Double,

                //附加信息
                 uuid: String,
                 scoring_year: Int,
                 interval_time: String,
                 interval_distance: Double,

                //去了不该去的消纳场
                 disposal_site_id: String,
                 disposal_site_name: String,
                 disposal_site_short_name: String,
                 disposal_type: String,
                 disposal_province_id: String,
                 disposal_city_id: String,
                 disposal_area_id: String,
                 disposal_address: String,
                 disposal_lng: Double,
                 disposal_lat: Double,

                 month_id: String,
                 day_id: String,
                 hour_id: String,
                //===========================车辆信息=========================
                ///////////////////////////////////////////////////////车辆
                vehicle_id: Int,       //车辆id------------------------->关联服务费、双向登记卡、运输证
                car_card_number: String,     //车牌号
                vehicle_model_id: String,    //车辆类型 16 重型自卸货车 17中型自卸货车
                vehicle_type_id: String,     //车辆种类（4综合执法车，5渣土车）
                vehicle_type_state: String,   //车辆类型状态
                vehicle_state: String,     //车辆状态(0：本地待命1：维修2：事故3：停用4：短盘5：审车6：营运7：异地待命8：停运9：禁运)
                if_new_energy: Int,     //是否新能源（0否、1是、3.国三 4国四，5国五）
                approved_tonnage: Float,      //核定吨位
                driver_id: Int,         //司机ID------------------------>关联司机表
                enterprise_id: String,      //公司ID------------------------>关联公司表
                dept_id: Int,           //监管单位ID------------------------>参与所有关联
                audit_state: String,
                manage_state: String,
                ////////////////////////////////////////////////司机信息
                driver_card_number: String,  //驾驶证
                driver_name: String,         //司机姓名

                ////////////////////////////////////////////////公司信息
                enterprise_name: String,    //公司名称
                department_id: Int,     //当前用户部门id
                address: String,           //公司地址
                enterprise_lng: Double,
                enterprise_lat: Double,
                enterprise_type_id: String,       //企业类型(( 1、施工单位 2、建设单位 3、运输单位 4、处置单位<消纳场处置单位、再生产品处置单位>)
                province_id: String,
                city_id: String,
                area_id: String,

                /////////////////////////////////////////////// 服务到期信息:ods_cwp_vehicle_service_fee
                service_state: Int,                     //'状态0 正常 1 过期'
                //////////////////////////////////////////////  定位区域:dim_cwp_area_coords
                region_id: String,                      //进入区域ID
                region_name: String,                    //进入区域名称
                //////////////////////////////////////////////
                at_work: Int,                  //1:从工地出来的，有效满载车辆
                ///////////////////////////////////////////////双向登记卡信息：ods_cwp_vehicle_register_card
                coords: String,                //路线  一辆车可以有多个双向登记卡，一张双向登记卡有多条路线：一张卡格式：[[[lng,lat],[lng,lat]],[[lng,lat],[lng,lat]]]  多张卡格式用|线分隔：[[[lng,lat],[lng,lat]],[[lng,lat],[lng,lat]]]|[[[lng,lat],[lng,lat]],[[lng,lat],[lng,lat]]]|.....
                register_card_state: Int,            //状态(1、正常 0、作废) --->对应表state
                ///////////////////////////////////////////////运输证信息：ods_cwp_vehicle_transport_card
                transport_card_state: Int,           //状态(0 已申请、1已批准、2已驳回、3作废)  --->对应表state

                    //===========================轨迹信息=========================
                devid: String, //设备id
                time: String, //设备产生时间
                lng: Double, //经度
                lat: Double, //纬度
                point: String,
                speed: String, //速度
                pos: String, //方向 0-360
                gpsflag: String, //定位状态:1:定位有效 0:定位失效
                wireflag: String, //天线状态（暂时不用）
                powerflag: String, //电源状态（0、关 1、开）
                accflag: String, //ACC状态（0、关 1、开）
                auxiliaryflag: String, //副发动机状态 1:启动 0：未启动
                t: String, //温度（暂时不用）
                door: String, //门状态（暂时不用）
                alldistance: String, //总里程（仪表中的里程）（暂时不用）
                alldistfuel: String, //不用
                gpsdistance: String, //GPS里程（使用）
                gpsalldistance: String, //gps清零后里程（暂时不用）
                createdatetime: String, //上报时间
                zxcqi: String, //??
                alarmflag: String, //1:报警 0:未报警
                mainoilper: String, //主油箱百分比
                residualoil: String, //主发剩余油量
                viceoilper: String, //副油箱百分比
                viceresidualoil: String, //副发动机剩余油量(升，小数点后两位)
                oildifference: String, //油量差
                sharpturn: String, //急转弯
                brake: String //急刹车
                    )
