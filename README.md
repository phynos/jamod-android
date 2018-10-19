# 基于jamod的Android拓展以及优化版本modbus类库
代码是抽出来的，本app暂不保证整体能编译通过，请直接使用代码（app模块的net包下）

## 特点
- 移除Android不支持的SCI串口代码
- 增加基于传统蓝牙的蓝牙串口（SPP）的RTU帧数据支持
- 增加基于蓝牙4.0通道(BLE)的RTU帧数据支持（需要针对蓝牙模块修改部分代码）
- 增加tcp-over-RTU模式

## RTU-over-TCP
- RTUTCPMasterConnection
- ModbusRTUTCPTransport
- ModbusRTUTCPTransaction

## RTU-Bluttooth-SPP
- RTUBluetoothMasterConnection
- ModbusRTUBluetoothTransport
- ModbusRTUBluetoothTransaction

## RTU-BLE
- RTUBLEMasterConnection
- ModbusRTUBLETransaction
- ModbusRTUBLETransport

## RTU-TCP
- TCPMasterConnection
- ModbusTCPTransaction
- ModbusTCPTransport