package com.example.shuzotakahashi.eegforthings


import android.app.Activity
import android.app.Service
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.choosemuse.libmuse.*
import com.choosemuse.libmuse.MuseDataPacketType.*
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.things.pio.PeripheralManagerService
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.collections.LinkedHashMap
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.DeadObjectException
import com.google.android.things.bluetooth.BluetoothProfileManager
import kotlin.math.log


// 脳波センサから送られてきた値を格納するMapを用意する。
// MapのValueは 受け取ったデータを格納するバッファー。
// MapのKeyは 対応するライブラリ内のEnumを利用する。
private val eegBufferMap = mutableMapOf(
        ALPHA_ABSOLUTE to arrayOfNulls<Double>(4),
        BETA_ABSOLUTE to arrayOfNulls(4),
        THETA_ABSOLUTE to arrayOfNulls(4),
        DELTA_ABSOLUTE to arrayOfNulls(4))

//バッファーにデータが格納してあるかを確認するBoolean を保持するマップ
private val hasEegData: HashMap<MuseDataPacketType, Boolean> = hashMapOf(
        ALPHA_ABSOLUTE to false, BETA_ABSOLUTE to false, THETA_ABSOLUTE to false, DELTA_ABSOLUTE to false)

// 集中していない状態に陥ったときの現在時刻
private var startTime: Long = 0

private val chartMap = linkedMapOf(
        "alpha" to Color.parseColor("#F44336"),
        "beta" to Color.parseColor("#FF9800"),
        "delta" to Color.parseColor("#00BCD4"),
        "theta" to Color.parseColor("#2196F3"))

class MainActivity : Activity() {

    private val TAG = MainActivity::class.java.simpleName
    private val handler = Handler()
    private val dataListener = DateListener()
    private val museManager = MuseManagerAndroid.getInstance()
    // private val chart = findViewById<LineChart>(R.id.lineChart)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        /* デバッグ用
           val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
           val isBtEnable = mBluetoothAdapter.isMuseEnabled

           val btProfile = BluetoothProfileManager()
           val enbaleList = btProfile.enabledProfiles
           val toEnable = listOf(BluetoothProfile.GATT)
           btProfile.enableProfiles(toEnable)*/


        chart.setDescription("") // グラフのタイトル。今回は空。
        chart.data = LineData() // 空のLineDataインスタンスを追加

        for (label in chartMap.keys) {
            val line = LineDataSet(null, label) // 新しく折れ線グラフを生成
            line.color = chartMap.getValue(label) //線の色を指定
            line.setDrawCircleHole(false) // エントリー部分に円を表示しない
            line.setDrawCircles(false)
            line.setDrawValues(false) // エントリー部分に値を表示しない

            chart.lineData.addDataSet(line) // 折れ線グラフを追加する
        }

        museManager.setContext(this)

        button.setOnClickListener {
            museManager.stopListening()

            val museList = museManager.muses//周囲にあるヘッドバンドのリストを取得する
            if (museList.size >= 1) {
                val muse = museList[0]
                muse.unregisterAllListeners() //以前登録されたリスナーをすべて解除
                muse.registerDataListener(dataListener, ALPHA_ABSOLUTE)
                muse.registerDataListener(dataListener, BETA_ABSOLUTE)
                muse.registerDataListener(dataListener, DELTA_ABSOLUTE)
                muse.registerDataListener(dataListener, THETA_ABSOLUTE)

                muse.runAsynchronously()// ヘッドバンドへの接続を開始し、データを非同期でストリーミングする。
                Toast.makeText(this, "successfully", Toast.LENGTH_LONG).show()
            } else {
                //周りに一つもヘッドセットが存在しない時
                Toast.makeText(this, "Headset is not found around", Toast.LENGTH_LONG).show()
            }
        }

        try {
            // postはLooperのキューにRunnableを追加する
            handler.post(tickUI)
        }catch (e : DeadObjectException){
            Log.d(TAG," DeadObjectException発生")
        }

        museManager.startListening()
    }


    override fun onDestroy() {
        super.onDestroy()
        museManager.stopListening()
    }

    private val tickUI: Runnable = object : Runnable {

        override fun run() {

            val eegValueMap = hashMapOf<MuseDataPacketType, Double>() //取得した脳波の値を保持する
            val data = chart.lineData

            for ((index, eegType) in arrayOf(ALPHA_ABSOLUTE, BETA_ABSOLUTE, DELTA_ABSOLUTE, THETA_ABSOLUTE).withIndex()) {
                if (hasEegData.getValue(eegType)) {
                    var eegValue = 0.0
                    eegBufferMap.getValue(eegType).filterNotNull()
                            .forEach { eegValue = it * 100 }
                    eegValue /= 4
                    eegValueMap.put(eegType, eegValue)

                    // Log.d(eegType.toString(),eegValue.toString())

                    //グラフの描写処理
                    val line = data.getDataSetByIndex(index)
                    data.addEntry(Entry(line.entryCount.toFloat(), eegValue.toFloat()), index)
                    data.notifyDataChanged()


                }
            }

            if (hasEegData.getValue(ALPHA_ABSOLUTE) && hasEegData.getValue(BETA_ABSOLUTE)
                    && hasEegData.getValue(THETA_ABSOLUTE) && hasEegData.getValue(DELTA_ABSOLUTE)) {

                // グラフの表示を更新する
                chart.notifyDataSetChanged()
                chart.setVisibleXRangeMaximum(50.toFloat())
                chart.moveViewToX(data.entryCount.toFloat())

                //　集中している状態ならカウンタを初期化する
                if ((eegValueMap.getValue(ALPHA_ABSOLUTE) + eegValueMap.getValue(BETA_ABSOLUTE))
                        > (eegValueMap.getValue(DELTA_ABSOLUTE) + eegValueMap.getValue(THETA_ABSOLUTE))) {

                    startTime = 0

                } else {

                    concentrateStatus.text = "NO"

                    //初めて集中していない状態に陥ったとき、その時の現在時刻を取得する
                    if (startTime == 0.toLong()) {
                        startTime = System.currentTimeMillis()
                    }

                    // 5秒間集中していない状態が続いたら、
                    if (System.currentTimeMillis() - startTime > 5000) {


                        /*
                        // TODO: サーボモータの動作処理のテストが必要
                        val service = PeripheralManagerService()
                        val pwm = service.openPwm("PWM0")
                        pwm.setPwmFrequencyHz(50.0) // 周波数を50Hzに
                        // 使用するSG92Rはだいたい 0.7ms〜2msのパルス幅で角度を指定する
                        // PWMの周期が20ms(つまり50HZ)のとき、3.5%〜10% 程度が指定するデューティ比となる
                        pwm.setPwmDutyCycle(1.35) //中心
                        pwm.setEnabled(true)*/
                    }
                }
            }

            //再帰的にLooperのキューに1000/60msの間隔で、このRunnableを追加する。
            handler.postDelayed(this, 1000 / 60)
        }
    }
}


class DateListener : MuseDataListener() {

    //TODO: 無理やり引数からnullableを消したけど、大丈夫？
    override fun receiveMuseDataPacket(p0: MuseDataPacket, p1: Muse) {

        val eegType = p0.packetType()
        val eegBuffer = eegBufferMap.getValue(eegType)
        for (i in 0..3) {
            // EEG1(左耳), EEG2(左額), EEG3(右額), EEG4(右耳)の値をバッファーの0-3に格納する
            // Eeg(enum)をvaluesメソッドで頭から順に格納した配列に変換している
            eegBuffer[i] = p0.getEegChannelValue(Eeg.values()[i])
            Log.d(eegType.toString(), p0.getEegChannelValue(Eeg.values()[i]).toString())
        }
        hasEegData.put(eegType, true)
    }

    // ヘッドセットが外された、目の瞬き...などが検出されたときに呼び出される。今回は実装なし。
    override fun receiveMuseArtifactPacket(p0: MuseArtifactPacket, p1: Muse) {}

}

