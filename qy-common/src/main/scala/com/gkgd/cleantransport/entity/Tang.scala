package com.gkgd.cleantransport.entity

/**
 * 趟数样例类
 */
case class Tang(device_id:String,
                time:String,
                gps_lng:String,
                gps_lat:String,
                speed:String,
                pos:String,
                gps_flag:String,
                wire_flag:String,
                power_flag:String,
                acc_flag:String,
                auxiliary_flag:String,
                temperature:String,
                door:String,
                all_distance:String,
                all_dist_fuel:String,
                gps_distance:String,
                gps_all_distance:String,
                report_time:String,
                zxcqi:String,
                alarm_flag:String,
                main_oil_per:String,
                residual_oil:String,
                vice_oil_per:String,
                vice_residual_oil:String,
                oil_difference:String,
                sharp_turn:String,
                brake:String,
                e_site_time:String,
                e_site_id:String,
                e_site_name:String,
                e_site_address:String,
                e_site_province_id:String,
                e_site_city_id:String,
                e_site_area_id:String,
                e_site_lat:Double,
                e_site_lng:Double,
                e_site_flag:Int,
                status:Int,
                dev_id:String,
                dev_status:Int,
                s_site_id:String,
                s_site_name:String,
                s_site_flag:Int,
                s_site_lng:Double,
                s_site_lat:Double,
                s_site_area_id:String,
                s_site_province_id:String,
                s_site_city_id:String,
                s_site_address:String,
                s_site_time:String,
                updatetime:String,
                dept_id:Int,
                vehicle_id:Int,
                transport_enterprise_id:String,
                car_card_number:String,
                load:Double,
                enterprise_name:String,
                province_id:String,
                city_id:String,
                area_id:String,
                vehicle_empty:Int,
                month_id:String,
                day_id:String,
                hour_id:String)
