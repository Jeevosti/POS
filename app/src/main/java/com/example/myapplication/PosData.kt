 package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Product(val id: String, val sku: String, val name: String, val unit: String, val price: Long)
data class Client(val id: String, val name: String, val phone: String, val address: String)
data class Warehouse(val id: String, val name: String)
data class Stock(val productId: String, val warehouseId: String, val qty: BigDecimal)
data class Invoice(
    val id: String, val code: String, val clientId: String, val clientName: String,
    val clientPhone: String, val warehouseId: String, val type: String,
    val total: Long, val paid: Long, val dueDate: String, val at: String
) {
    val due: Long get() = total - paid
    val status: String get() = if (due == 0L) "PAID" else if (paid > 0L) "PART PAID" else "UNPAID"
}
data class InvoiceLine(
    val invoiceId: String, val name: String, val sku: String, val unit: String,
    val qty: BigDecimal, val price: Long, val total: Long
)
data class Payment(val invoiceId: String, val amount: Long, val method: String, val reference: String, val at: String, val actor: String)
data class Project(
    val id: String, val code: String, val invoiceId: String, val clientId: String,
    val name: String, val location: String, val performedBy: String, val date: String
)
data class Movement(
    val id: String, val productId: String, val warehouseId: String, val delta: BigDecimal,
    val kind: String, val reference: String, val invoiceId: String, val note: String, val at: String, val actor: String
)
data class Audit(val action: String, val summary: String, val reference: String, val at: String, val actor: String)
data class CartLine(val product: Product, val qty: BigDecimal) {
    val total: Long get() = MoneyMath.lineTotal(product.price, qty)
}

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
private fun JSONObject.decimal(key: String) = BigDecimal(get(key).toString())
private fun <T> JSONObject.list(key: String, parse: (JSONObject) -> T): List<T> {
    val items = optJSONArray(key) ?: JSONArray()
    return List(items.length()) { parse(items.getJSONObject(it)) }
}

class PosData(raw: JSONObject) {
    val supportsOpeningStock: Boolean = raw.optInt("schema_version",1) >= 2
    val me = raw.getJSONObject("me")
    val staffName: String = me.getString("name")
    val admin: Boolean = me.getString("role") == "admin"
    private val staff = raw.list("staff") { it.getString("id") to it.getString("name") }.toMap()
    val products = raw.list("products") { Product(it.getString("id"), it.getString("sku"), it.getString("name"), it.getString("unit"), it.getLong("price_cents")) }
    val clients = raw.list("clients") { Client(it.getString("id"), it.getString("name"), it.getString("phone"), it.getString("address")) }
    val warehouses = raw.list("warehouses") { Warehouse(it.getString("id"), it.getString("name")) }
    val stock = raw.list("stock") { Stock(it.getString("product_id"), it.getString("warehouse_id"), it.decimal("qty")) }
    val invoices = raw.list("invoices") { Invoice(it.getString("id"), it.getString("code"), it.getString("client_id"), it.getString("client_name"), it.getString("client_phone"), it.getString("warehouse_id"), it.getString("sale_type"), it.getLong("total_cents"), it.getLong("paid_cents"), it.text("due_date"), it.getString("created_at")) }
    val lines = raw.list("lines") { InvoiceLine(it.getString("invoice_id"), it.getString("product_name"), it.getString("sku"), it.getString("unit"), it.decimal("qty"), it.getLong("price_cents"), it.getLong("line_total_cents")) }
    val payments = raw.list("payments") { Payment(it.getString("invoice_id"), it.getLong("amount_cents"), it.getString("method"), it.getString("reference"), it.getString("created_at"), it.getString("created_by")) }
    val projects = raw.list("projects") { Project(it.getString("id"), it.getString("code"), it.getString("invoice_id"), it.getString("client_id"), it.getString("name"), it.getString("location"), it.getString("performed_by"), it.getString("completed_date")) }
    val movements = raw.list("movements") { Movement(it.getString("id"), it.getString("product_id"), it.getString("warehouse_id"), it.decimal("delta"), it.getString("kind"), it.getString("reference_id"), it.text("invoice_id"), it.getString("note"), it.getString("created_at"), it.getString("created_by")) }
    val audit = raw.list("audit") { Audit(it.getString("action"), it.getString("summary"), it.getString("reference_id"), it.getString("created_at"), it.getString("created_by")) }
    fun actor(id: String) = staff[id] ?: id
    fun available(productId: String, warehouseId: String) = stock.firstOrNull { it.productId == productId && it.warehouseId == warehouseId }?.qty ?: BigDecimal.ZERO
    fun totalAvailable(productId: String) = stock.filter { it.productId == productId }.fold(BigDecimal.ZERO) { acc, s -> acc + s.qty }
    fun warehouse(id: String) = warehouses.firstOrNull { it.id == id }?.name ?: id

    fun product(id: String) = products.firstOrNull { it.id == id }?.name ?: id
    fun invoice(id: String) = invoices.firstOrNull { it.id == id }
    fun projectForInvoice(id: String) = projects.firstOrNull { it.invoiceId == id }
}

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }.format(Date())
fun displayTime(raw: String): String = try {
    val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val date = utc.parse(raw.take(19)) ?: throw IllegalArgumentException()
    SimpleDateFormat("dd MMM yyyy, HH:mm 'EAT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }.format(date)
} catch (_: Exception) { raw }
