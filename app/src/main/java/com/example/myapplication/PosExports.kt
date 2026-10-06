package com.example.myapplication

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun requireDate(value: String) {
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Use a date such as 2026-10-06." }
    val format=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply { isLenient=false }
    require(runCatching { format.parse(value) }.getOrNull()!=null) { "That calendar date does not exist." }
}
fun localDate(raw: String): String = try {
    val utc=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }
    val parsed=utc.parse(raw.take(19)) ?: throw IllegalArgumentException()
    SimpleDateFormat("yyyy-MM-dd",Locale.US).apply { timeZone=TimeZone.getTimeZone("Africa/Nairobi") }.format(parsed)
} catch (_:Exception) { raw.take(10) }
fun inPeriod(date: String, from: String, to: String) = (from.isBlank() || date>=from) && (to.isBlank() || date<=to)

/** Produces a real multipage PDF using Android's built-in PDF writer. */
fun writeInvoicePdf(context: Context, uri: Uri, invoice: Invoice, data: PosData) {
    val document=PdfDocument()
    var page: PdfDocument.Page?=null
    var number=0
    var y=110f
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(20,45,66); textSize=12f }
    fun nextPage() {
        page?.let { document.finishPage(it) }
        number++
        page=document.startPage(PdfDocument.PageInfo.Builder(595,842,number).create())
        paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD); paint.textSize=20f
        page!!.canvas.drawText(PosConfig.COMPANY_NAME,48f,52f,paint)
        paint.typeface=Typeface.DEFAULT; paint.textSize=12f
        page!!.canvas.drawText("${invoice.code} / INVOICE / Page $number",48f,78f,paint)
        y=110f
    }
    fun write(text: String, bold: Boolean=false) {
        paint.typeface=Typeface.create(Typeface.DEFAULT,if(bold) Typeface.BOLD else Typeface.NORMAL)
        for (part in text.split('\n')) {
            var remaining=part
            if(remaining.isEmpty()) { y+=18; continue }
            while(remaining.isNotEmpty()) {
                if(y>785f) nextPage()
                paint.typeface=Typeface.create(Typeface.DEFAULT,if(bold) Typeface.BOLD else Typeface.NORMAL)
                val chars=paint.breakText(remaining,true,499f,null).coerceAtLeast(1)
                page!!.canvas.drawText(remaining.take(chars),48f,y,paint)
                remaining=remaining.drop(chars); y+=18
            }
        }
    }
    try {
        nextPage()
        write("Client: ${invoice.clientName}",true)
        write("Phone: ${invoice.clientPhone}")
        write("Issued: ${displayTime(invoice.at)}")
        write("Warehouse: ${data.warehouse(invoice.warehouseId)}")
        write("Sale type: ${invoice.type.uppercase()} / ${invoice.status}")
        if(invoice.dueDate.isNotBlank()) write("Due date: ${invoice.dueDate}")
        write("")
        write("GOODS",true)
        data.lines.filter { it.invoiceId==invoice.id }.forEach { line ->
            write("${line.sku} / ${line.name}")
            write("${MoneyMath.units(line.qty)} ${line.unit} x ${MoneyMath.money(line.price)} = ${MoneyMath.money(line.total)}")
        }
        write("")
        write("Original invoice total: ${MoneyMath.money(invoice.total)}",true)
        write("Payments received: ${MoneyMath.money(invoice.paid)}")
        write("Outstanding balance: ${MoneyMath.money(invoice.due)}",true)
        write("")
        write("PAYMENT HISTORY",true)
        val payments=data.payments.filter { it.invoiceId==invoice.id }
        if(payments.isEmpty()) write("No payment received yet.")
        payments.forEach { write("${MoneyMath.money(it.amount)} / ${it.method} / ${it.reference}\n${displayTime(it.at)} / ${data.actor(it.actor)}") }
        data.projectForInvoice(invoice.id)?.let { p ->
            write(""); write("PROJECT ${p.code}",true)
            write("${p.name}\nClient: ${invoice.clientName}\nWorkers: ${p.performedBy}\nLocation: ${p.location}\nCompleted: ${p.date}")
        }
        write(""); write("Balance reflects the records loaded when this PDF was generated.")
        page?.let { document.finishPage(it) }; page=null
        val output=context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Could not open the selected file.")
        output.use { document.writeTo(it) }
    } finally { document.close() }
}

private fun amount(cents: Long) = BigDecimal.valueOf(cents,2)
private fun csvCell(value: Any): String {
    var text=value.toString()
    // Protect text cells from spreadsheet formula interpretation. Numeric cells
    // remain numeric, including signed warehouse movement quantities.
    if(value !is Number && text.trimStart().firstOrNull() in listOf('=','+','-','@')) text="'"+text
    return "\""+text.replace("\"","\"\"")+"\""
}
fun reportCsv(kind: String, data: PosData, from: String, to: String): String {
    val rows=mutableListOf<List<Any>>()
    val invoices=data.invoices.filter { inPeriod(localDate(it.at),from,to) }
    when(kind) {
        "Sales","Invoice credit" -> {
            rows.add(listOf("Invoice","Client","Issued EAT","Sale type","Original total KES","Paid KES","Current due KES","Due date","Status","Project"))
            invoices.filter { kind=="Sales" || it.due>0 }.forEach { i -> rows.add(listOf(i.code,i.clientName,displayTime(i.at),i.type,amount(i.total),amount(i.paid),amount(i.due),i.dueDate,i.status,data.projectForInvoice(i.id)?.code ?: "")) }
        }
        "Payments" -> {
            rows.add(listOf("Invoice","Client","Received EAT","Amount KES","Method","Reference","Recorded by"))
            data.payments.filter { inPeriod(localDate(it.at),from,to) }.forEach { p -> rows.add(listOf(data.invoice(p.invoiceId)?.code ?: p.invoiceId,data.invoice(p.invoiceId)?.clientName ?: "",displayTime(p.at),amount(p.amount),p.method,p.reference,data.actor(p.actor))) }
        }
        "Client credit" -> {
            rows.add(listOf("Client","Phone","Outstanding invoices","Current total credit KES"))
            data.clients.forEach { c -> val outstanding=data.invoices.filter { it.clientId==c.id && it.due>0 }; rows.add(listOf(c.name,c.phone,outstanding.size,amount(outstanding.sumOf { it.due }))) }
        }
        "Stock" -> {
            rows.add(listOf("Warehouse","SKU","Product","Unit","Current quantity"))
            data.warehouses.forEach { w -> data.products.forEach { p -> rows.add(listOf(w.name,p.sku,p.name,p.unit,data.available(p.id,w.id))) } }
        }
        "Projects" -> {
            rows.add(listOf("Project ID","Project","Client","Goods invoice","Workers","Location","Completed","Invoice total KES","Current invoice due KES"))
            data.projects.filter { inPeriod(it.date,from,to) }.forEach { p -> val i=data.invoice(p.invoiceId); rows.add(listOf(p.code,p.name,i?.clientName ?: "",i?.code ?: p.invoiceId,p.performedBy,p.location,p.date,amount(i?.total ?: 0L),amount(i?.due ?: 0L))) }
        }
        "Movements" -> {
            rows.add(listOf("Movement ID","Time EAT","Kind","Warehouse","Product","Delta","Note","Invoice","Project","Reference ID","Recorded by"))
            data.movements.filter { inPeriod(localDate(it.at),from,to) }.forEach { m -> rows.add(listOf(m.id,displayTime(m.at),m.kind,data.warehouse(m.warehouseId),data.product(m.productId),m.delta,m.note,data.invoice(m.invoiceId)?.code ?: "",data.projectForInvoice(m.invoiceId)?.code ?: "",m.reference,data.actor(m.actor))) }
        }
        "Activity" -> {
            rows.add(listOf("Time EAT","Action","Summary","Reference ID","Recorded by"))
            data.audit.filter { inPeriod(localDate(it.at),from,to) }.forEach { a -> rows.add(listOf(displayTime(a.at),a.action,a.summary,a.reference,data.actor(a.actor))) }
        }
        else -> throw IllegalArgumentException("Choose a report type.")
    }
    return "\uFEFF"+rows.joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } }+"\r\n"
}
