package com.example.shuzotakahashi.eegforthings


import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import com.choosemuse.libmuse.*
import com.choosemuse.libmuse.MuseDataPacketType.*
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.things.pio.PeripheralManagerService
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*


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

class MainActivity : Activity() {

    private val museManager = MuseManagerAndroid.getInstance()
    private val dataListener = DateListener()
    private val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setActionBar(toolbar)

        museManager.setContext(this)

        // fabを押したら、周囲からヘッドセットを探して、接続を開始します。
        // その時受信する脳波の種類やデータを決めます(反応するリスナを決める) 。
        fab.setOnClickListener {
            museManager.stopListening()

            val museList = museManager.muses//周囲にあるヘッドバンドのリストを取得する
            if (museList.size >= 1) {
                val muse = museList[0]
                muse.unregisterAllListeners() //以前登録されたリスナをすべて解除
                muse.registerDataListener(dataListener, ALPHA_ABSOLUTE) // α波
                muse.registerDataListener(dataListener, BETA_ABSOLUTE) // β波
                muse.registerDataListener(dataListener, DELTA_ABSOLUTE) // δ波
                muse.registerDataListener(dataListener, THETA_ABSOLUTE) //θ波

                muse.runAsynchronously()// ヘッドバンドとの接続を開始し、データを非同期でストリーミングする。
                Toast.makeText(this, "successfully", Toast.LENGTH_LONG).show()
            } else {
                //周りに一つもヘッドセットが存在しない時
                Toast.makeText(this, "Headset is not found around", Toast.LENGTH_LONG).show()
            }
        }

        // グラフをセットアップする
        chart.setDescription("") // グラフのタイトル。今回は空。
        chart.data = LineData() // 空のLineDataインスタンスを追加

        // グラフのラベルと色のマップ
        val chartMap = linkedMapOf(
                "alpha" to Color.parseColor("#F44336"),
                "beta" to Color.parseColor("#FF9800"),
                "delta" to Color.parseColor("#00BCD4"),
                "theta" to Color.parseColor("#2196F3"))

        for (label in chartMap.keys) {
            val line = LineDataSet(null, label) // 新しく折れ線グラフを生成
            line.color = chartMap.getValue(label) //線の色を指定
            line.setDrawCircleHole(false) // エントリー部分に円を表示しない
            line.setDrawCircles(false)
            line.setDrawValues(false) // エントリー部分に値を表示しない

            chart.lineData.addDataSet(line) // 折れ線グラフを追加する
        }

    }

    override fun onStart() {

        museManager.startListening()
        //　LooperのキューにRunnableを追加する
        handler.post(tickUI)

        super.onStart()
    }


    override fun onDestroy() {
        museManager.stopListening()
        super.onDestroy()
    }

    private val tickUI: Runnable = object : Runnable {

        override fun run() {

            //取得した脳波の値を保持する
            val eegValueMap = hashMapOf<MuseDataPacketType, Double>()
            val data = chart.lineData

            // 各脳波（α波、β波、θ波、δ波 ）のバッファーには（左耳、左額、右額、右頬）の4つのセンサの値が格納されている。
            // その4つの値を統合して一つの値にし、グラフに渡して描写していく。
            for ((index, eegType) in arrayOf(ALPHA_ABSOLUTE, BETA_ABSOLUTE, DELTA_ABSOLUTE, THETA_ABSOLUTE).withIndex()) {
                // 脳波の値はバッファーに格納してあるか？
                if (hasEegData.getValue(eegType)) {
                    // 統合した値を格納する変数を用意。
                    // それぞれ値を100倍して、すべて足す。
                    // それを4で割った値を統合した値とする。
                    var eegValue = 0.0
                    eegBufferMap.getValue(eegType).filterNotNull()
                            .forEach { eegValue = it * 100 }
                    eegValue /= 4
                    eegValueMap.put(eegType, eegValue)

                    //グラフに値をセットする
                    val line = data.getDataSetByIndex(index)
                    data.addEntry(Entry(line.entryCount.toFloat(), eegValue.toFloat()), index)
                    data.notifyDataChanged()
                }
            }

            // すべての脳波のデータがちゃんと受信された状態か？
            if (hasEegData.getValue(ALPHA_ABSOLUTE) && hasEegData.getValue(BETA_ABSOLUTE)
                    && hasEegData.getValue(THETA_ABSOLUTE) && hasEegData.getValue(DELTA_ABSOLUTE)) {

                // グラフの表示を更新する
                chart.notifyDataSetChanged()
                chart.setVisibleXRangeMaximum(50.toFloat())
                chart.moveViewToX(data.entryCount.toFloat())

                //　眠い状態か？
                if ((eegValueMap.getValue(ALPHA_ABSOLUTE) + eegValueMap.getValue(BETA_ABSOLUTE))
                        < (eegValueMap.getValue(DELTA_ABSOLUTE) + eegValueMap.getValue(THETA_ABSOLUTE))) {

                    // 眠い状態なら
                    // 状態を示すTextViewの値を更新する
                    awakeningStatus.text = "Sleepy"
                    awakeningStatus.setTextColor(Color.parseColor("#F44336"))

                    //　初めて（もしくは再び）眠い状態に陥ったとき、現在時刻を取得する
                    if (startTime == 0.toLong()) {
                        startTime = System.currentTimeMillis()
                    }


                    // 5秒間眠い状態が状態が続いたら、部屋の照明を消す。
                    if (System.currentTimeMillis() - startTime > 5000) {

                        // 部屋の照明を消す処理
                        val service = PeripheralManagerService()
                        val pwm = service.openPwm("PWM0")
                        pwm.setPwmFrequencyHz(50.0) // 周波数を50Hzに
                        pwm.setPwmDutyCycle(6.1) // 70°動かす
                        pwm.setEnabled(true)
                        onDestroy()

                    }

                } else {
                    //眠くなくて覚醒状態のとき

                    // 状態を示すTextViewの値を更新する
                    awakeningStatus.text = "Awakening"
                    awakeningStatus.setTextColor(Color.parseColor("#76c13a"))

                    // 眠くなった時刻を保持する変数を0にする
                    startTime = 0
                }
            }

            //再帰的にLooperのキューに1000/60msの間隔で、このRunnableを追加する。
            handler.postDelayed(this, 1000 / 60)
        }
    }

}


class DateListener : MuseDataListener() {
    //データを受信するときに利用するリスナー
    override fun receiveMuseDataPacket(p0: MuseDataPacket, p1: Muse) {
        val eegType = p0.packetType()
        val eegBuffer = eegBufferMap.getValue(eegType)
        for (i in 0..3) {
            // EEG1(左耳), EEG2(左額), EEG3(右額), EEG4(右耳)の値をバッファーの0-3に格納する
            // Eeg(enum)をvaluesメソッドで頭から順に格納した配列に変換している
            eegBuffer[i] = p0.getEegChannelValue(Eeg.values()[i])
        }
        hasEegData[eegType] = true
    }

    // ヘッドセットが外された、目の瞬き...などが検出されたときに呼び出される。今回は実装なし。
    override fun receiveMuseArtifactPacket(p0: MuseArtifactPacket, p1: Muse) {}
}

