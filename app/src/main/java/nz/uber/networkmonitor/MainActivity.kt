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
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=Color.rgb(13,20,34)
  window.navigationBarColor=Color.rgb(13,20,34)
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(16)); setBackgroundColor(Color.rgb(13,20,34)) }
  val brand=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
  val mark=text("◉",34f,Color.rgb(84,217,174),true)
  brand.addView(mark)
  brand.addView(text("  PULSE  /  Network Monitor",20f,Color.WHITE,true))
  root.addView(brand)
  root.addView(text("Your connection, at a glance",13f,Color.rgb(155,169,190)).apply { setPadding(0,dp(5),0,dp(22)) })
  statusCard=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(20),dp(28),dp(20),dp(28)); background=bg(Color.rgb(40,62,65),dp(24).toFloat()) }
  status=text("●  CHECKING",29f,Color.WHITE,true).apply { gravity=Gravity.CENTER }
  subtitle=text("Waiting for first network test",14f,Color.WHITE).apply { gravity=Gravity.CENTER; setPadding(0,dp(10),0,0) }
  statusCard.addView(status); statusCard.addView(subtitle)
  root.addView(statusCard,LinearLayout.LayoutParams(-1,dp(180)))
  root.addView(text("LIVE MONITORING",12f,Color.rgb(155,169,190),true).apply { setPadding(0,dp(24),0,dp(10)) })
  fun action(title:String,color:Int,click:()->Unit) {
   val b=Button(this).apply { text=title; textSize=14f; isAllCaps=false; setTextColor(Color.WHITE); background=bg(color,dp(14).toFloat()); setOnClickListener { click() } }
   root.addView(b,LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(10) })
  }
  action("▶  Start monitoring",Color.rgb(27,137,105)) { val i=Intent(this,MonitorService::class.java).setAction("START"); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("state","CHECKING").apply(); updateStatus() }
  action("■  Stop monitoring",Color.rgb(65,77,99)) { startService(Intent(this,MonitorService::class.java).setAction("STOP")); getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("state","STOPPED").apply(); updateStatus(); refresh() }
  action("＋  Mark missed Uber request",Color.rgb(45,66,100)) { MonitorService.append(this,"UBER_MARKER","manual","","User marked suspected missed request"); refresh(); Toast.makeText(this,"Marker saved",Toast.LENGTH_SHORT).show() }
  action("▤  View outage report",Color.rgb(45,66,100)) { showReport() }
  action("↻  Refresh history",Color.rgb(45,66,100)) { refresh() }
  action("⇧  Share CSV report",Color.rgb(45,66,100)) { val file=File(filesDir,"events.csv"); if(!file.exists()) { Toast.makeText(this,"No report yet",Toast.LENGTH_SHORT).show(); return@action }; val intent=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,file.readText()) }; startActivity(Intent.createChooser(intent,"Share monitoring report")) }
  root.addView(text("RECENT ACTIVITY  ·  NZ TIME",12f,Color.rgb(155,169,190),true).apply { setPadding(0,dp(14),0,dp(8)) })
  log=text("No readings yet",12f,Color.rgb(213,223,235)).apply { setTextIsSelectable(true); setPadding(dp(14),dp(12),dp(14),dp(12)); background=bg(Color.rgb(25,36,54),dp(14).toFloat()) }
  root.addView(log)
  val scroll=ScrollView(this).apply { fillViewport=true; addView(root) }
  setContentView(scroll)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)
  refresh(); updateStatus()
 }
 private fun updateStatus() {
  val value=getSharedPreferences("monitor",MODE_PRIVATE).getString("state","STOPPED") ?: "STOPPED"
  val (label,description,color)=when(value) {
   "OK"->Triple("●  CONNECTED","Internet is accessible",Color.rgb(24,133,99))
   "DOWN"->Triple("●  NETWORK DOWN","Connected, but internet is not accessible",Color.rgb(179,48,62))
   "PAUSED"->Triple("Ⅱ  PAUSED","No Wi-Fi or mobile data — checks paused",Color.rgb(79,91,110))
   "CHECKING"->Triple("◌  CHECKING","Testing internet connectivity…",Color.rgb(48,98,157))
   else->Triple("○  NOT RUNNING","Tap Start monitoring to begin",Color.rgb(79,91,110))
  }
  status.text=label; subtitle.text=description; statusCard.background=bg(color,dp(24).toFloat())
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
   if(event=="SESSION_STOP" || event=="SESSION_START" || event=="MONITORING_GAP") {
    if(started!=null) { report.append("Incomplete outage (monitoring interrupted): ${fmt.format(started)}\n\n"); started=null }
   }
  }
  if(started!=null) report.append("Ongoing / unconfirmed end: ${fmt.format(started)}\n")
  report.append("\nCompleted outages: $count\nTotal confirmed duration: ${total/60}m ${total%60}s")
  log.text=report.toString()
 }
 private fun refresh() { val f=File(filesDir,"events.csv"); log.text=if(f.exists()) f.readLines().takeLast(35).joinToString("\n") else "No readings yet" }
}