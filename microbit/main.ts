// Weather board program for micro:bit V2 on the DFRobot Environment Science board.
// It reads the board's sensors directly (no DFRobot extension needed) and sends
// one line of readings over Bluetooth every 10 seconds to the MAXHUB app.
//
// Setup in MakeCode (makecode.microbit.org):
//   1. New project -> switch to JavaScript -> paste this whole file.
//   2. Extensions -> add "bluetooth" (if asked to remove "radio", say yes).
//   3. Project Settings (gear icon) -> choose "No Pairing Required".
//   4. Download to the micro:bit V2 that sits on the weather board.
//
// LED screen: a tick = the app is connected, a cross = waiting for the app.

let connected = false

bluetooth.onBluetoothConnected(function () {
    connected = true
    basic.showIcon(IconNames.Yes)
})

bluetooth.onBluetoothDisconnected(function () {
    connected = false
    basic.showIcon(IconNames.No)
})

bluetooth.startUartService()
basic.showIcon(IconNames.No)

// Same sensor protocol the DFRobot extension uses: write 8 to address 0x10,
// then read 22 bytes. Values are built the same way the extension builds them.
function readBoard(): string {
    pins.i2cWriteNumber(0x10, 8, NumberFormat.Int8LE)
    let d = pins.i2cReadBuffer(0x10, 22)
    basic.pause(50)

    let temp = ""
    if (d[8] == 1) {
        temp = d[9] + "." + d[10]
    } else {
        temp = "-" + d[9] + "." + (255 - d[10])
    }
    let hum = d[11] + "." + d[12]
    let pressureKpa = ((d[13] << 16) | (d[14] << 8) | d[15]) / 1000
    let uv = ""
    if (d[1] < 10) {
        uv = d[0] + ".0" + d[1]
    } else {
        uv = d[0] + "." + d[1]
    }
    let light = (d[2] << 8) | d[3]

    return "T:" + temp + ",H:" + hum + ",P:" + pressureKpa + ",U:" + uv + ",L:" + light
}

basic.forever(function () {
    let line = readBoard()
    if (connected) {
        bluetooth.uartWriteLine(line)
    }
    basic.pause(10000)
})
