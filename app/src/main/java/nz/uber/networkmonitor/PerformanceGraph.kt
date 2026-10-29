package nz.uber.networkmonitor

import android.content.Context
import android.graphics.*
import android.view.View
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PerformanceGraph(context:Context):View(context) {
 private val paint=Paint(3)
 private val green=Color.rgb(39,174,96)
 private val yellow=Color.rgb(226,166,32)
 private val red=Color.rgb(215,61,72)
 private val gray=Color.rgb(180,191,204)
 private data class Sample(val at:Instant,val ping:Float,val quality:String)
 private val timeFormat=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Pacific/Auckland"))
 override fun onDraw(canvas:Canvas) {
  super.onDraw(canvas)
  val w=width.toFloat(); val h=height.toFloat()
  paint.color=Color.WHITE; canvas.drawColor(Color.WHITE)
  val left=46f*resources.displayMetrics.density; val right=w-14f*resources.displayMetrics.density
  val top=20f*resources.displayMetrics.density; val bottom=h-34f*resources.displayMetrics.density
  val file=File(context.filesDir,"events.csv")
  val samples=if(file.exists()) file.readLines().drop(1).mapNotNull { line ->
   val fields=line.split(",",limit=5)
   if(fields.size<5 || fields[1]!="OK") null else try {
    val ping=fields[3].toFloat()
    val quality=Regex("quality=([A-Z]+)").find(fields[4])?.groupValues?.get(1) ?: "GOOD"
    Sample(Instant.parse(fields[0]),ping,quality)
   } catch(_:Exception) { null }
  }.takeLast(60) else emptyList()
  paint.textSize=12f*resources.displayMetrics.scaledDensity
  paint.color=Color.rgb(110,125,145)
  if(samples.isEmpty()) { canvas.drawText("Start monitoring to see your ping graph",left, h/2,paint); return }
  val maxPing=maxOf(200f,(samples.maxOfOrNull { it.ping } ?: 200f)*1.15f)
  for(i in 0..4) {
   val y=top+(bottom-top)*i/4
   paint.color=Color.rgb(229,235,242); paint.strokeWidth=1f
   canvas.drawLine(left,y,right,y,paint)
   paint.color=Color.rgb(110,125,145)
   canvas.drawText("${(maxPing*(4-i)/4).toInt()}",4f*resources.displayMetrics.density,y+4f,paint)
  }
  val path=Path()
  samples.forEachIndexed { i,s ->
   val x=left+(right-left)*i/maxOf(1,samples.size-1)
   val y=bottom-(s.ping/maxPing)*(bottom-top)
   if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
  }
  paint.color=green; paint.style=Paint.Style.STROKE; paint.strokeWidth=3f*resources.displayMetrics.density
  canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
  samples.forEachIndexed { i,s ->
   val x=left+(right-left)*i/maxOf(1,samples.size-1)
   val y=bottom-(s.ping/maxPing)*(bottom-top)
   paint.color=when(s.quality) { "POOR"->red; "FAIR"->yellow; else->green }
   canvas.drawCircle(x,y,3.5f*resources.displayMetrics.density,paint)
  }
  paint.color=Color.rgb(110,125,145)
  canvas.drawText(timeFormat.format(samples.first().at),left,bottom+21f*resources.displayMetrics.density,paint)
  val end=timeFormat.format(samples.last().at)
  canvas.drawText(end,right-paint.measureText(end),bottom+21f*resources.displayMetrics.density,paint)
 }
}
