# 基于jamod的Android拓展以及优化版本modbus类库
本项目是从光伏助手APP中去掉业务提取出来的modbus类库

## 特点
- 移除Android不支持的SCI串口代码
- 增加基于蓝牙4.0通道的支持（需要针对蓝牙模块修改部分代码）
- 增加tcp-RT模式