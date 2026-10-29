package nz.uber.networkmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
 private lateinit var log: TextView
 private lateinit var status: TextView
 private lateinit var subtitle: TextView
 private lateinit var statusCard: LinearLayout
 private val handler=android.os.Handler(android.os.Looper.getMainLooper())
 private val ticker=object:Runnable { override fun run() { if(::status.isInitialized) updateStatus(); handler.postDelayed(this,2000) } }
 private fun bg(color:Int, radius:Float=24f):GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=radius }
 private fun dp(v:Int):Int=(v*resources.displayMetrics.density).toInt()
 private fun text(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply { text=value; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD) }
 private lateinit var networkInfo:TextView
 private lateinit var play:Button
 private lateinit var graph:PerformanceGraph
 private lateinit var metrics:TextView
 private val green=Color.rgb(39,174,96)
 private val red=Color.rgb(215,61,72)
 private val ink=Color.rgb(35,48,64)
 private val muted=Color.rgb(115,128,143)
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=Color.WHITE
  window.navigationBarColor=Color.WHITE
  window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(18),dp(22),dp(12)); setBackgroundColor(Color.rgb(248,250,253)) }
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
  header.addView(text("◉",35f,green,true).apply { gravity=Gravity.CENTER })
  header.addView(text("PULSE",24f,ink,true).apply { gravity=Gravity.CENTER })
  header.addView(text("NETWORK MONITOR",10f,muted,true).apply { gravity=Gravity.CENTER })
  root.addView(header)
  val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)); background=bg(Color.WHITE,dp(18).toFloat()); elevation=dp(3).toFloat() }
  val cardParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(22) }
  root.addView(card,cardParams)
  card.addView(text("CONNECTION DETAILS",12f,muted,true))
  networkInfo=text("Detecting network…",15f,ink).apply { setPadding(0,dp(12),0,dp(14)) }
  card.addView(networkInfo)
  statusCard=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(12),dp(20),dp(12),dp(20)); background=bg(Color.rgb(238,242,247),dp(16).toFloat()) }
  status=text("○  NOT RUNNING",24f,ink,true).apply { gravity=Gravity.CENTER }
  subtitle=text("Press Start to monitor",13f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(7),0,0) }
  statusCard.addView(status); statusCard.addView(subtitle)
  card.addView(statusCard,LinearLayout.LayoutParams(-1,dp(126)))
  val tip=text("Internet check every 5 seconds · NZ time",12f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(16),0,0) }
  card.addView(tip)
  metrics=text("Ping —   •   Jitter —   •   Failed checks —",13f,ink,true).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(8)) }
  root.addView(metrics)
  root.addView(text("PING HISTORY  ·  NZ TIME",12f,muted,true).apply { setPadding(0,dp(8),0,dp(6)) })
  graph=PerformanceGraph(this)
  root.addView(graph,LinearLayout.LayoutParams(-1,dp(160)))
  val middle=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
  val space=LinearLayout.LayoutParams(-1,0,1f)
  root.addView(middle,space)
  play=Button(this).apply { text="▶"; textSize=30f; isAllCaps=false; setTextColor(Color.WHITE); background=bg(green,dp(90).toFloat()); elevation=dp(5).toFloat(); setOnClickListener {
   val current=getSharedPreferences("monitor",MODE_PRIVATE).getString("state","STOPPED")
   if(current=="STOPPED") { val i=Intent(this@MainActivity,MonitorService::class.java).setAction("START"); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("state","CHECKING").apply() }
   else { startService(Intent(this@MainActivity,MonitorService::class.java).setAction("STOP")); getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("state","STOPPED").apply() }
   updateStatus()
  } }
  middle.addView(play,LinearLayout.LayoutParams(dp(92),dp(92)))
  middle.addView(text("Tap to start or stop",12f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,0) })
  val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,dp(10),0,dp(6)) }
  fun tab(label:String,click:()->Unit) {
   val b=Button(this).apply { text=label; textSize=12f; isAllCaps=false; setTextColor(ink); background=bg(Color.rgb(234,240,247),dp(14).toFloat()); setOnClickListener { click() } }
   nav.addView(b,LinearLayout.LayoutParams(0,dp(64),1f).apply { marginEnd=dp(5) })
  }
  tab("◉\nMonitor") { refresh() }
  tab("◷\nHistory") { showReport() }
  tab("＋\nUber mark") { MonitorService.append(this,"UBER_MARKER","manual","","User marked suspected missed request"); Toast.makeText(this,"Marker saved",Toast.LENGTH_SHORT).show(); refresh() }
  tab("⚙\nMore") {
   val options=arrayOf("Refresh readings","Share CSV","Outage report")
   android.app.AlertDialog.Builder(this).setTitle("PULSE tools").setItems(options) { _,which ->
    when(which) {
     0->refresh()
     1->{ val file=File(filesDir,"events.csv"); if(file.exists()) { val intent=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,file.readText()) }; startActivity(Intent.createChooser(intent,"Share CSV report")) } else Toast.makeText(this,"No report yet",Toast.LENGTH_SHORT).show() }
     2->showReport()
    }
   }.show()
  }
  root.addView(nav)
  log=text("No readings yet",11f,muted).apply { setTextIsSelectable(true); setPadding(dp(10),dp(8),dp(10),dp(8)); background=bg(Color.WHITE,dp(12).toFloat()); maxLines=5 }
  root.addView(log,LinearLayout.LayoutParams(-1,dp(82)))
  setContentView(root)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)
  refresh(); updateStatus()
 }
 private fun updateStatus() {
  val value=getSharedPreferences("monitor",MODE_PRIVATE).getString("state","STOPPED") ?: "STOPPED"
  val (label,description,color)=when(value) {
   "OK"-> {
    val quality=getSharedPreferences("monitor",MODE_PRIVATE).getString("quality","GOOD")
    if(quality=="FAIR" || quality=="POOR") Triple("⚠  SLOW NETWORK","High ping, jitter or failed checks",Color.rgb(211,155,26))
    else Triple("●  INTERNET OK","Connected and responsive",green)
   }
   "DOWN"->Triple("●  NETWORK FAILED","Connected, but internet inaccessible",red)
   "PAUSED"->Triple("Ⅱ  PAUSED","No Wi-Fi or mobile data",muted)
   "CHECKING"->Triple("◌  CHECKING","Testing internet accessibility",Color.rgb(51,117,186))
   else->Triple("○  NOT RUNNING","Press Start to monitor",muted)
  }
  status.text=label; status.setTextColor(color); subtitle.text=description
  statusCard.background=bg(Color.argb(23,Color.red(color),Color.green(color),Color.blue(color)),dp(16).toFloat())
  val pref=getSharedPreferences("monitor",MODE_PRIVATE)
  val ping=pref.getLong("ping",-1L)
  val jitter=pref.getLong("jitter",-1L)
  val loss=pref.getFloat("loss",0f)
  metrics.text="Ping: ${if(ping>=0) "${ping} ms" else "—"}   •   Jitter: ${if(jitter>=0) "${jitter} ms" else "—"}   •   Failed: ${"%.1f".format(java.util.Locale.US,loss)}%"
  if(::graph.isInitialized) graph.invalidate()
  play.text=if(value=="STOPPED") "▶" else "■"
  play.background=bg(if(value=="STOPPED") green else red,dp(90).toFloat())
  val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
  val caps=cm.getNetworkCapabilities(cm.activeNetwork)
  val wifi=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
  val cell=caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true
  networkInfo.text=when { wifi->"◉  Wi-Fi connected\nInternet status shown below"; cell->"▥  Mobile data connected\nInternet status shown below"; else->"○  Wi-Fi / Mobile data disconnected" }
 }
 override fun onResume() { super.onResume(); if(::log.isInitialized) refresh(); handler.post(ticker) }
 override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
 private fun showReport() {
  val f=File(filesDir,"events.csv")
  if(!f.exists()) { log.text="No recorded sessions yet"; return }
  val zone=ZoneId.of("Pacific/Auckland")
  val fmt=DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a").withZone(zone)
  val rows=f.readLines().drop(1).mapNotNull { line ->
   val parts=line.split(",",limit=5)
   if(parts.size<2) null else try { Pair(Instant.parse(parts[0]),parts[1]) } catch(_:Exception) { null }
  }
  val report=StringBuilder("NETWORK OUTAGE REPORT (New Zealand time)\n\n")
  var started:Instant?=null
  var total=0L
  var count=0
  for((time,event) in rows) {
   if(event=="OUTAGE_STARTED" && started==null) started=time
   if(event=="NETWORK_RESTORED" && started!=null) {
    val begin=started!!
    val seconds=java.time.Duration.between(begin,time).seconds.coerceAtLeast(0)
    count++; total+=seconds
    report.append("${fmt.format(begin)} → ${fmt.format(time)}\nDuration: ${seconds/60}m ${seconds%60}s\n\n")
    started=null
   }
   if(event=="SESSION_STOP" || event=="SESSION_START" || event=="MONITORING_GAP" || event=="OUTAGE_INTERRUPTED") {
    if(started!=null) { report.append("Incomplete outage (monitoring interrupted): ${fmt.format(started)}\n\n"); started=null }
   }
  }
  if(started!=null) report.append("Ongoing / unconfirmed end: ${fmt.format(started)}\n")
  report.append("\nCompleted outages: $count\nTotal confirmed duration: ${total/60}m ${total%60}s")
  log.text=report.toString()
 }
 private fun refresh() { val f=File(filesDir,"events.csv"); log.text=if(f.exists()) f.readLines().takeLast(35).joinToString("\n") else "No readings yet" }
}