package com.example.demo.component;


import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.demo.entity.ExcelData;
import com.example.demo.mqtt.SimpleMqttClient;
import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.RequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class BACnetComponent {

    private static final List<ExcelData> EXCEL_DATA = new ArrayList<>();

    @Autowired
    private SimpleMqttClient simpleMqttClient;

    @PostConstruct
    public void initOpcData() throws Exception {
        ClassPathResource classPathResource = new ClassPathResource("file/设备信息汇总表2021-12-22_17_23_07.xlsx");
        // 判断文件是否存在
        boolean exists = classPathResource.exists();
        if (!exists) {
            log.error("找不到点位文件！");
            throw new Exception("找不到点位文件！");
        }
        InputStream inputStream1 = classPathResource.getInputStream();
        List<ExcelData> list1 = EasyExcel.read(inputStream1).head(ExcelData.class).sheet(0).doReadSync();
        EXCEL_DATA.addAll(list1);
        log.info("读取bac设备点位表成功！");
    }

    @Scheduled(cron = "0 0/5 * * * ?")
    public void handleBACnet() {
        log.info("=======开始读取BACnet数据========");
        LocalDevice d = null;
        try {
            //创建网络对象
            IpNetwork ipNetwork = new IpNetworkBuilder()
                    .withLocalBindAddress("192.168.50.31")
                    .withSubnet("255.255.255.0", 24)
                    .withPort(47808)
                    .withReuseAddress(true)
                    .build();
            //创建虚拟的本地设备，deviceNumber随意
            d = new LocalDevice(12580, new DefaultTransport(ipNetwork));
            d.initialize();
            d.startRemoteDeviceDiscovery();

            JSONArray result = new JSONArray();
            for (ExcelData excelData : EXCEL_DATA) {
                JSONObject jsonObject = new JSONObject();
                String code = excelData.getCode();
                Integer deviceId = Integer.parseInt(excelData.getCode().split("_")[0]);
                String name = excelData.getName();
                List<String> attrList = Arrays.asList(excelData.getKey().split("/"));
                jsonObject.put("deviceId", deviceId);
                jsonObject.put("code", code);
                jsonObject.put("name", name);
                //获取远程设备，instanceNumber 是设备的device id
                RemoteDevice rd = null;
                try {
                    rd = d.getRemoteDeviceBlocking(deviceId);
                } catch (BACnetException e) {
                    log.error("deviceId=<{}>读取远程设备时候出错", deviceId);
                    jsonObject.put("data", null);
                    result.add(jsonObject);
                    continue;
                }
                List<ObjectIdentifier> objectList = RequestUtils.getObjectList(d, rd).getValues();
                jsonObject.put("data", analysisAttr(objectList, d, rd, attrList));
                result.add(jsonObject);
            }
            d.terminate();
            simpleMqttClient.publish("/iot/wfsBAC/gateway/realtimeData", JSON.toJSONBytes(result), 0, false);
            log.info("=======读取BACnet数据成功========");
        } catch (Exception e) {
            e.printStackTrace();
            if (d != null) {
                d.terminate();
            }
            log.error("====读取opc数据发生异常！====");
        }
    }

    /**
     * 解析字段
     *
     * @return
     */
    private JSONObject analysisAttr(List<ObjectIdentifier> objectList, LocalDevice d, RemoteDevice rd, List<String> attrList) throws BACnetException {
        JSONObject result = new JSONObject();
        for (ObjectIdentifier objectIdentifier : objectList) {
            String name = ((Encodable) RequestUtils.getProperty(d, rd, objectIdentifier, PropertyIdentifier.objectName)).toString();
            if (attrList.contains(name)) {
                String value = ((Encodable) RequestUtils.getProperty(d, rd, objectIdentifier, PropertyIdentifier.presentValue)).toString();
                result.put(name, value);
            }
        }
        return result;
    }

}
