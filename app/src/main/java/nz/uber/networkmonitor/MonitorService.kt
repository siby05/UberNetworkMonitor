package nz.uber.networkmonitor

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitorService : Service() {
 private val executor=Executors.newSingleThreadScheduledExecutor()
 private var running=false
 private var lastCheck=0L
 private var failures=0
 private var outage=false
 private var lastAlert=0L
 private var paused=false
 private val alertChannel="network_alerts"
 override fun onBind(intent:Intent?) : IBinder? = null
 override fun onStartCommand(intent:Intent?, flags:Int, startId:Int):Int {
  if(intent?.action=="STOP") { running=false; append(this,"SESSION_STOP","","",""); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
  if(running) return START_STICKY
  running=true
  val channel="network_monitor"
  val nm=getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel(channel,"Active network monitoring",NotificationManager.IMPORTANCE_LOW))
  nm.createNotificationChannel(NotificationChannel(alertChannel,"Network outage alerts",NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,null) })
  val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
  val notification=Notification.Builder(this,channel).setContentTitle("Uber Network Monitor active").setContentText("Checking connection every 5 seconds (low data)").setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pending).setOngoing(true).build()
  startForeground(1,notification)
  append(this,"SESSION_START","","","Monitoring Android via iPhone hotspot")
  executor.scheduleWithFixedDelay({ if(running) check() },0,5,TimeUnit.SECONDS)
  return START_STICKY
 }
 private fun check() {
  val now=System.currentTimeMillis()
  if(!paused && lastCheck!=0L && now-lastCheck>20000) { if(outage) { outage=false; failures=0; append(this,"OUTAGE_INTERRUPTED","","","Monitoring gap; restoration unconfirmed") } }
  lastCheck=now
  val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
  val caps=cm.getNetworkCapabilities(cm.activeNetwork)
  val wifi=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
  val transport=if(wifi) "WIFI" else if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true) "CELLULAR" else "OTHER_OR_DISCONNECTED"
  val cellular=caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true
  if(!wifi && !cellular) {
   if(!paused) {
    paused=true
    if(outage) { outage=false; append(this,"OUTAGE_INTERRUPTED","","","Transport disconnected; no confirmed restoration") }
    failures=0
    // No internet probes, outage alerts, or report entries while disconnected.
   }
   return
  }
  if(paused) { paused=false; failures=0; lastCheck=now }
  var latency=""
  var result="INTERNET_FAILURE"
  var details=""
  try {
   val start=System.nanoTime()
   val c=(URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection)
   c.connectTimeout=3000; c.readTimeout=3000; c.useCaches=false; c.instanceFollowRedirects=false
   try { val status=c.responseCode; latency=((System.nanoTime()-start)/1000000).toString(); result=if(status==204) "OK" else "HTTP_UNEXPECTED"; details="HTTP $status" } finally { c.disconnect() }
  } catch(e:Exception) { details=e.javaClass.simpleName }
  record(result,transport,latency,details)
  updateAlert(result=="OK",if(result=="OK") "Internet restored" else "Internet check failed ($result)")
 }
 private fun updateAlert(success:Boolean, reason:String) {
  val now=System.currentTimeMillis()
  if(success) {
   failures=0
   if(outage) { outage=false; append(this,"NETWORK_RESTORED","","",reason); notifyAlert("Network restored","Internet connection is working again") }
   return
  }
  failures++
  if(failures>=2) {
   if(!outage) { outage=true; append(this,"OUTAGE_STARTED","","",reason); notifyAlert("NETWORK DOWN",reason); lastAlert=now }
   else if(now-lastAlert>=30000) { notifyAlert("NETWORK STILL DOWN",reason); lastAlert=now }
  }
 }
 private fun notifyAlert(title:String,message:String) {
  val manager=getSystemService(NotificationManager::class.java)
  val notification=Notification.Builder(this,alertChannel).setSmallIcon(android.R.drawable.stat_notify_error)
   .setContentTitle(title).setContentText(message).setAutoCancel(true)
   .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).build()
  manager.notify(2,notification)
 }
 private fun record(status:String,transport:String,latency:String,details:String) { append(this,status,transport,latency,details) }
 override fun onDestroy() { running=false; executor.shutdownNow(); super.onDestroy() }
 companion object {
  @Synchronized fun append(ctx:Context,status:String,transport:String,latency:String,details:String) {
   val f=File(ctx.filesDir,"events.csv")
   if(!f.exists()) f.writeText("timestamp_utc,event,transport,latency_ms,details\n")
   val safe=details.replace('"','\'').replace('\n',' ')
   f.appendText("${Instant.now()},$status,$transport,$latency,\"$safe\"\n")
  }
 }
}