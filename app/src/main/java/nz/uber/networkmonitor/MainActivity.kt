package nz.uber.networkmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
 private lateinit var log: TextView
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,32,28,20) }
  fun button(title:String, action:()->Unit) { layout.addView(Button(this).apply { text=title; setOnClickListener { action() } }) }
  layout.addView(TextView(this).apply { text="Uber Network Monitor"; textSize=24f })
  layout.addView(TextView(this).apply { text="Connect Android to your iPhone hotspot. Monitoring continues via a foreground notification. Gaps are not classified as outages." })
  button("START MONITORING") { val i=Intent(this,MonitorService::class.java).setAction("START"); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i) }
  button("STOP MONITORING") { startService(Intent(this,MonitorService::class.java).setAction("STOP")); refresh() }
  button("MARK MISSED UBER REQUEST") { MonitorService.append(this,"UBER_MARKER","manual","","User marked suspected missed request"); refresh() }
  button("VIEW OUTAGE REPORT") { showReport() }
  button("REFRESH HISTORY") { refresh() }
  button("SHARE CSV REPORT") { val file=File(filesDir,"events.csv"); if(!file.exists()) return@button; val intent=Intent(Intent.ACTION_SEND).apply { type="text/csv"; putExtra(Intent.EXTRA_TEXT,file.readText()) }; startActivity(Intent.createChooser(intent,"Share monitoring report as text")) }
  log=TextView(this).apply { textSize=12f; setTextIsSelectable(true) }
  val scroll=ScrollView(this); scroll.addView(log); layout.addView(scroll)
  setContentView(layout)
  if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)
  refresh()
 }
 override fun onResume() { super.onResume(); if(::log.isInitialized) refresh() }
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