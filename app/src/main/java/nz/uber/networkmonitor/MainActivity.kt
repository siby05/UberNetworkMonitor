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
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(8),dp(18),dp(8)); setBackgroundColor(Color.rgb(248,250,253)) }
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
  header.addView(text("⌁  ◉  ⌁",26f,green,true).apply { gravity=Gravity.CENTER })
  header.addView(text("PULSE",21f,ink,true).apply { gravity=Gravity.CENTER })
  header.addView(text("NETWORK MONITOR",10f,muted,true).apply { gravity=Gravity.CENTER })
  val headerContainer=android.widget.FrameLayout(this)
  headerContainer.addView(header,android.widget.FrameLayout.LayoutParams(-1,-2))
  root.addView(headerContainer)
  val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(10)); background=bg(Color.WHITE,dp(18).toFloat()); elevation=dp(3).toFloat() }
  val cardParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) }
  root.addView(card,cardParams)
  card.addView(text("CONNECTION DETAILS",12f,muted,true))
  networkInfo=text("Detecting network…",15f,ink).apply { setPadding(0,dp(6),0,dp(7)) }
  card.addView(networkInfo)
  statusCard=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(10),dp(8),dp(10),dp(8)); background=bg(Color.rgb(238,242,247),dp(16).toFloat()) }
  status=text("○  NOT RUNNING",21f,ink,true).apply { gravity=Gravity.CENTER }
  subtitle=text("Press Start to monitor",13f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(7),0,0) }
  statusCard.addView(status); statusCard.addView(subtitle)
  card.addView(statusCard,LinearLayout.LayoutParams(-1,dp(88)))
  val tip=text("Internet check every 5 seconds · NZ time",12f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) }
  card.addView(tip)
  metrics=text("Response —   •   Variation —   •   Failed checks —",13f,ink,true).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(8)) }
  root.addView(metrics)
  root.addView(text("RESPONSE TIME  ·  LAST 60 SUCCESSFUL CHECKS",12f,muted,true).apply { setPadding(0,dp(8),0,dp(6)) })
  root.addView(text("Lower is better  •  Green: good  •  Amber: fair  •  Red: poor",11f,muted))
  graph=PerformanceGraph(this)
  root.addView(graph,LinearLayout.LayoutParams(-1,dp(220)).apply { topMargin=dp(8); bottomMargin=dp(12) })
  val middle=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; background=bg(Color.WHITE,dp(18).toFloat()) }
  // Bottom action is anchored outside the scrollable dashboard.
  play=Button(this).apply { text="▶"; textSize=23f; isAllCaps=false; setTextColor(Color.WHITE); background=bg(green,dp(90).toFloat()); elevation=dp(5).toFloat(); setOnClickListener {
   val current=getSharedPreferences("monitor",MODE_PRIVATE).getString("state","STOPPED")
   if(current=="STOPPED") { val i=Intent(this@MainActivity,MonitorService::class.java).setAction("START"); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("state","CHECKING").apply() }
   else { startService(Intent(this@MainActivity,MonitorService::class.java).setAction("STOP")); getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("state","STOPPED").apply() }
   updateStatus()
  } }
  middle.addView(play,LinearLayout.LayoutParams(dp(76),dp(76)))
  middle.addView(text("Tap to start or stop",12f,muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(2),0,0) })
  // Navigation lives in the top-right hamburger menu to preserve dashboard space.
  val menuButton=Button(this).apply {
   text="☰"; textSize=25f; isAllCaps=false; contentDescription="Open navigation menu"
   setTextColor(ink); background=bg(Color.rgb(234,240,247),dp(12).toFloat())
   setOnClickListener {
    val items=arrayOf("Monitor · Live dashboard","History · Network summary","Share CSV report","Outage report","Refresh readings")
    android.app.AlertDialog.Builder(this@MainActivity).setTitle("PULSE Menu").setItems(items) { _,which ->
     when(which) {
      0->refresh()
      1->showReport()
      2->{ val file=File(filesDir,"events.csv"); if(file.exists()) { val share=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,file.readText()) }; startActivity(Intent.createChooser(share,"Share CSV report")) } else Toast.makeText(this@MainActivity,"No report yet",Toast.LENGTH_SHORT).show() }
      3->showReport()
      4->refresh()
     }
    }.show()
   }
  }
  val menuParams=android.widget.FrameLayout.LayoutParams(dp(52),dp(48),Gravity.TOP or Gravity.RIGHT)
  headerContainer.addView(menuButton,menuParams)
  log=text("No readings yet",11f,muted).apply { setTextIsSelectable(true); setPadding(dp(10),dp(8),dp(10),dp(8)); background=bg(Color.WHITE,dp(12).toFloat()); maxLines=5 }
  log.visibility=View.GONE
  // History is displayed in a dialog; do not reserve hidden dashboard space.
  val outer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(248,250,253)) }
  val scroll=ScrollView(this).apply { isFillViewport=false; clipToPadding=false }
  scroll.addView(root)
  outer.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  outer.addView(middle,LinearLayout.LayoutParams(-1,dp(112)).apply { bottomMargin=dp(8) })
  setContentView(outer)
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
  metrics.text="Response: ${if(ping>=0) "${ping} ms" else "—"}   •   Variation: ${if(jitter>=0) "${jitter} ms" else "—"}   •   Failed: ${"%.1f".format(java.util.Locale.US,loss)}%"
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
  val file=File(filesDir,"events.csv")
  if(!file.exists()) { android.app.AlertDialog.Builder(this).setTitle("Network Summary").setMessage("No monitoring data recorded yet").setPositiveButton("Close",null).show(); return }
  data class Event(val time:Instant,val kind:String,val latency:Long?)
  val events=file.readLines().drop(1).mapNotNull { line ->
   val p=line.split(",",limit=5)
   if(p.size<4) null else try { Event(Instant.parse(p[0]),p[1],p[3].toLongOrNull()) } catch(_:Exception) { null }
  }
  if(events.isEmpty()) { android.app.AlertDialog.Builder(this).setTitle("Network Summary").setMessage("No monitoring data recorded yet").setPositiveButton("Close",null).show(); return }
  val fmt=DateTimeFormatter.ofPattern("dd MMM, hh:mm a").withZone(ZoneId.of("Pacific/Auckland"))
  val checks=events.filter { it.kind=="OK" || it.kind=="INTERNET_FAILURE" || it.kind=="HTTP_UNEXPECTED" }
  val success=checks.count { it.kind=="OK" }
  val failed=checks.size-success
  val pct=if(checks.isEmpty()) 0.0 else failed*100.0/checks.size
  val latencies=checks.filter { it.kind=="OK" }.mapNotNull { it.latency }
  val avg=if(latencies.isEmpty()) "—" else "${latencies.average().toLong()} ms"
  val slow=events.count { it.kind=="SLOW_NETWORK" }
  var outageStart:Instant?=null
  var outageCount=0
  var confirmedSeconds=0L
  var incomplete=0
  val important=mutableListOf<String>()
  var sessionStart:Instant?=null
  var monitoredSeconds=0L
  for(e in events) {
   when(e.kind) {
    "SESSION_START" -> { if(sessionStart!=null) monitoredSeconds+=java.time.Duration.between(sessionStart,e.time).seconds.coerceAtLeast(0); sessionStart=e.time }
    "SESSION_STOP" -> { if(sessionStart!=null) monitoredSeconds+=java.time.Duration.between(sessionStart,e.time).seconds.coerceAtLeast(0); sessionStart=null }
   }
   when(e.kind) {
    "OUTAGE_STARTED" -> { outageStart=e.time; important.add("🔴 ${fmt.format(e.time)}  Network down") }
    "NETWORK_RESTORED" -> {
     if(outageStart!=null) { confirmedSeconds+=java.time.Duration.between(outageStart,e.time).seconds.coerceAtLeast(0); outageCount++; outageStart=null }
     important.add("🟢 ${fmt.format(e.time)}  Network restored")
    }
    "OUTAGE_INTERRUPTED","MONITORING_GAP" -> {
     if(outageStart!=null) { incomplete++; outageStart=null }
     important.add("⚪ ${fmt.format(e.time)}  Monitoring interrupted")
    }
    "SLOW_NETWORK" -> important.add("🟠 ${fmt.format(e.time)}  Slow network")
    "NETWORK_QUALITY_RECOVERED" -> important.add("🟢 ${fmt.format(e.time)}  Speed improved")
   }
  }
  if(sessionStart!=null) monitoredSeconds+=java.time.Duration.between(sessionStart,Instant.now()).seconds.coerceAtLeast(0)
  if(outageStart!=null) incomplete++
  val span=if(monitoredSeconds>0) "${monitoredSeconds/3600}h ${monitoredSeconds%3600/60}m" else "Not available"
  val report=StringBuilder()
  report.append("OVERVIEW · NZ TIME\n")
  report.append("${fmt.format(events.first().time)} – ${fmt.format(events.last().time)}\n\n")
  report.append("Monitoring duration: $span\n")
  report.append("Total checks: ${checks.size}\n")
  report.append("Successful: $success  •  Failed: $failed\n")
  report.append("Failed checks: ${"%.1f".format(java.util.Locale.US,pct)}%\n")
  report.append("Average response: $avg\n\n")
  report.append("NETWORK EVENTS\n")
  report.append("Slow network alerts: $slow\n")
  report.append("Confirmed outages: $outageCount\n")
  report.append("Confirmed downtime: ${confirmedSeconds/60}m ${confirmedSeconds%60}s\n")
  if(incomplete>0) report.append("Incomplete / ongoing outages: $incomplete\n")
  report.append("\nIMPORTANT EVENTS (LATEST 12)\n")
  report.append(if(important.isEmpty()) "No major network events recorded" else important.takeLast(12).asReversed().joinToString("\n"))
  report.append("\n\nNote: Response time is an HTTPS check, not ICMP ping. Duration excludes known stopped periods but may include monitoring gaps.")
  val scroll=ScrollView(this)
  scroll.addView(text(report.toString(),14f,ink).apply { setPadding(dp(20),dp(16),dp(20),dp(16)); setTextIsSelectable(true) })
  android.app.AlertDialog.Builder(this).setTitle("PULSE · Network Health Summary").setView(scroll).setPositiveButton("Close",null).show()
 }
 private fun refresh() { if(::graph.isInitialized) graph.invalidate(); log.visibility=View.GONE }
}