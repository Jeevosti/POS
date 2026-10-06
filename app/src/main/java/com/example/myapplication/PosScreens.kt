package com.example.myapplication

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun InvoicesScreen(model: PosController, data: PosData) {
    var pendingOnly by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var paymentId by remember { mutableStateOf("") }
    var detailId by remember { mutableStateOf("") }
    var exportId by rememberSaveable { mutableStateOf("") }
    val context=LocalContext.current
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val current=model.data; val invoice=current?.invoice(exportId)
        if(uri!=null && current!=null && invoice!=null) model.exportFile { writeInvoicePdf(context,uri,invoice,current) }
    }
    Heading("Invoices & credit", "Payments reduce the invoice balance and the client's overall credit.")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!pendingOnly,{ pendingOnly=false },label={ Text("All invoices") })
        FilterChip(pendingOnly,{ pendingOnly=true },label={ Text("Outstanding") })
    }
    Input("Search invoice or client",search,{ search=it })
    val invoices=data.invoices.filter { (!pendingOnly || it.due>0) && (it.code.contains(search,true) || it.clientName.contains(search,true)) }
    if(invoices.isEmpty()) Text("No matching invoices.")
    invoices.forEach { invoice -> RecordCard("${invoice.code} · ${invoice.clientName}") {
        Text("${invoice.type.uppercase()} · ${invoice.status} · ${displayTime(invoice.at)}")
        Text("Total: ${MoneyMath.money(invoice.total)} · Paid: ${MoneyMath.money(invoice.paid)}")
        Text("Outstanding: ${MoneyMath.money(invoice.due)}",fontWeight=FontWeight.Bold,color=if(invoice.due>0) Color(0xFFAC6212) else Teal)
        if(invoice.dueDate.isNotBlank()) Text("Due: ${invoice.dueDate}")
        data.projectForInvoice(invoice.id)?.let { Text("Project: ${it.code} / ${it.name}") }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick={ detailId=invoice.id }) { Text("Details") }
            if(invoice.due>0) TextButton(onClick={ paymentId=invoice.id },enabled=!model.busy && !model.hasPending) { Text("Record payment") }
            TextButton(onClick={ exportId=invoice.id; pdf.launch("${invoice.code}.pdf") },enabled=!model.busy) { Text("Save PDF") }
        }
    } }
    data.invoice(paymentId)?.let { PaymentDialog(model,it) { paymentId="" } }
    data.invoice(detailId)?.let { invoice -> AlertDialog(onDismissRequest={ detailId="" },title={ Text(invoice.code) },
        text={ Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(invoice.clientName,fontWeight=FontWeight.Bold); Text(invoice.clientPhone)
            Text("From: ${data.warehouse(invoice.warehouseId)}")
            data.lines.filter { it.invoiceId==invoice.id }.forEach { line ->
                Text("${line.name}\n${MoneyMath.units(line.qty)} ${line.unit} × ${MoneyMath.money(line.price)} = ${MoneyMath.money(line.total)}")
            }
            Text("Original total: ${MoneyMath.money(invoice.total)}")
            Text("Paid: ${MoneyMath.money(invoice.paid)}")
            Text("Amount due: ${MoneyMath.money(invoice.due)}",fontWeight=FontWeight.Bold)
            data.payments.filter { it.invoiceId==invoice.id }.forEach { p -> Text("${MoneyMath.money(p.amount)} · ${p.method} ${p.reference}\n${displayTime(p.at)} · ${data.actor(p.actor)}") }
            data.projectForInvoice(invoice.id)?.let { Text("${it.code}: ${it.name}\n${it.performedBy}\n${it.location} · ${it.date}") }
        } },confirmButton={ TextButton(onClick={ detailId="" }) { Text("Close") } }) }
}

@Composable private fun PaymentDialog(model: PosController, invoice: Invoice, close: () -> Unit) {
    var amount by remember { mutableStateOf("") }; var method by remember { mutableStateOf("Cash") }; var reference by remember { mutableStateOf("") }
    FormDialog("Payment / ${invoice.code}",model,close,{ checked(model) {
        val cents=MoneyMath.cents(amount); require(cents>0 && cents<=invoice.due) { "Enter a positive payment within the outstanding balance." }
        model.command("add_payment",payload("invoice_id" to invoice.id,"amount_cents" to cents,"method" to method,"reference" to reference),close)
    } }) {
        Text("${invoice.clientName}\nOutstanding: ${MoneyMath.money(invoice.due)}")
        Input("Payment received (KES)",amount,{ amount=it },true)
        Picker("Method",methods(),method,{ method=it }); Input("Payment reference (optional)",reference,{ reference=it })
        Text("Records money already received; it does not charge a bank or M-Pesa account.")
    }
}

@Composable internal fun ProjectsScreen(model: PosController, data: PosData) {
    var create by remember { mutableStateOf(false) }
    Heading("Completed projects", "Workers, location, client, date and warehouse goods invoice.")
    Button(onClick={ create=true },enabled=!model.busy && !model.hasPending) { Text("Log completed project") }
    if(data.projects.isEmpty()) Text("Create the goods invoice in Checkout, then log the project here.")
    data.projects.forEach { project -> RecordCard("${project.code} · ${project.name}") {
        val invoice=data.invoice(project.invoiceId)
        Text("Client: ${invoice?.clientName ?: project.clientId}")
        Text("Performed by: ${project.performedBy}"); Text("Location: ${project.location}"); Text("Completed: ${project.date}")
        Text("Goods invoice: ${invoice?.code}",color=Teal,fontWeight=FontWeight.Bold)
        data.lines.filter { it.invoiceId==project.invoiceId }.forEach { Text("${it.name}: ${MoneyMath.units(it.qty)} ${it.unit}") }
        if(invoice!=null) Text("Invoice balance: ${MoneyMath.money(invoice.due)}")
    } }
    if(create) ProjectDialog(model,data) { create=false }
}
@Composable private fun ProjectDialog(model: PosController, data: PosData, close: () -> Unit) {
    var invoiceId by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }; var workers by remember { mutableStateOf("") }; var date by remember { mutableStateOf(today()) }
    val available=data.invoices.filter { invoice -> data.projects.none { it.invoiceId==invoice.id } }
    FormDialog("Log completed project",model,close,{ checked(model) {
        require(invoiceId.isNotBlank() && name.isNotBlank() && location.isNotBlank() && workers.isNotBlank()) { "Fill in the invoice, project, location and workers." }
        requireDate(date); require(date<=today()) { "Completion cannot be in the future." }
        model.command("log_project",payload("invoice_id" to invoiceId,"name" to name.trim(),"location" to location.trim(),"performed_by" to workers.trim(),"completed_date" to date),close)
    } }) {
        Picker("Goods invoice",available.map { it.id to "${it.code} / ${it.clientName}" },invoiceId,{ invoiceId=it })
        data.invoice(invoiceId)?.let { Text("Client: ${it.clientName}. Goods were already dispatched on this invoice.") }
        Input("Project name",name,{ name=it }); Input("Where work was done",location,{ location=it })
        Input("Workers / technicians",workers,{ workers=it }); Input("Completed date (YYYY-MM-DD)",date,{ date=it })
        Text("A unique project ID is generated and linked to this invoice. Stock is not deducted again.")
    }
}

@Composable internal fun ClientsScreen(model: PosController, data: PosData) {
    var add by remember { mutableStateOf(false) }; var search by rememberSaveable { mutableStateOf("") }
    var activeSearch by rememberSaveable { mutableStateOf("") }
    Heading("Clients", "Client details and total outstanding credit.")
    Button(onClick={ add=true },enabled=!model.busy && !model.hasPending) { Text("Add client") }
    SearchField("Name, phone or address",search,{ search=it },{ activeSearch=search.trim() })
    if(search.isNotEmpty() || activeSearch.isNotEmpty()) TextButton(onClick={ search=""; activeSearch="" }) { Text("Clear search") }
    val clients=data.clients.filter { it.name.contains(activeSearch,true) || it.phone.contains(activeSearch,true) || it.address.contains(activeSearch,true) }
    if(clients.isEmpty()) Text(if(data.clients.isEmpty()) "No clients yet. Add your first client." else "No clients match your search.")
    clients.forEach { client -> RecordCard(client.name) {
        Text(client.phone); if(client.address.isNotBlank()) Text(client.address)
        val outstanding=data.invoices.filter { it.clientId==client.id && it.due>0 }
        Text("Total credit: ${MoneyMath.money(outstanding.sumOf { it.due })}",color=Teal,fontWeight=FontWeight.Bold)
        outstanding.forEach { Text("${it.code}: ${MoneyMath.money(it.due)}") }
    } }
    if(add) {
        var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }
        FormDialog("Add client",model,{ add=false },{ checked(model) {
            require(name.isNotBlank()) { "Enter the client name." }
            model.command("add_client",payload("name" to name.trim(),"phone" to phone.trim(),"address" to address.trim())) { add=false }
        } }) { Input("Client name",name,{ name=it }); Input("Phone",phone,{ phone=it }); Input("Address",address,{ address=it }) }
    }
}

@Composable internal fun LogsScreen(data: PosData) {
    var type by rememberSaveable { mutableStateOf("Goods movements") }; var search by rememberSaveable { mutableStateOf("") }
    Heading("Company logs", "Each operation records the staff account and time.")
    Picker("Log",listOf("Goods movements","Activity").map { it to it },type,{ type=it })
    Input("Search log",search,{ search=it })
    if(type=="Goods movements") data.movements.filter { (it.note+it.kind+data.product(it.productId)).contains(search,true) }.forEach { row -> RecordCard("${row.kind.uppercase()} · ${data.product(row.productId)}") {
        Text("${if(row.delta.signum()>0) "+" else ""}${MoneyMath.units(row.delta)} · ${data.warehouse(row.warehouseId)}",fontWeight=FontWeight.Bold)
        Text(row.note); Text("${data.actor(row.actor)} · ${displayTime(row.at)}")
        data.invoice(row.invoiceId)?.let { invoice -> Text("Invoice: ${invoice.code}"); data.projectForInvoice(invoice.id)?.let { Text("Project: ${it.code}") } }
        Text("Movement ID: ${row.id}",style=MaterialTheme.typography.bodySmall)
        Text("Reference ID: ${row.reference}",style=MaterialTheme.typography.bodySmall)
    } } else data.audit.filter { (it.summary+it.action+data.actor(it.actor)).contains(search,true) }.forEach { row -> RecordCard(row.summary) {
        Text(row.action); Text("${data.actor(row.actor)} · ${displayTime(row.at)}"); Text("Record ID: ${row.reference}",style=MaterialTheme.typography.bodySmall)
    } }
}

@Composable internal fun ReportsScreen(model: PosController, data: PosData) {
    var kind by rememberSaveable { mutableStateOf("Sales") }; var from by rememberSaveable { mutableStateOf("") }; var to by rememberSaveable { mutableStateOf("") }
    var activeFrom by rememberSaveable { mutableStateOf("") }; var activeTo by rememberSaveable { mutableStateOf("") }
    val context=LocalContext.current
    val csv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if(uri!=null) { val content=model.csvContent; if(content.isNotEmpty()) model.exportFile { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("Could not open the selected file.") } }
    }
    Heading("Reports", "Date filters use Kenyan time. Leave dates blank for all time.")
    Input("From (YYYY-MM-DD, optional)",from,{ from=it }); Input("To (YYYY-MM-DD, optional)",to,{ to=it })
    OutlinedButton(onClick={ checked(model) {
        if(from.isNotBlank()) requireDate(from); if(to.isNotBlank()) requireDate(to)
        require(from.isBlank() || to.isBlank() || from<=to) { "From date must be before To date." }; activeFrom=from; activeTo=to
    } }) { Text("Apply dates") }
    Text("Applied period: ${activeFrom.ifBlank { "Beginning" }} to ${activeTo.ifBlank { "Now" }}")
    val invoices=data.invoices.filter { inPeriod(localDate(it.at),activeFrom,activeTo) }
    val payments=data.payments.filter { inPeriod(localDate(it.at),activeFrom,activeTo) }
    val projects=data.projects.filter { inPeriod(it.date,activeFrom,activeTo) }
    Metric("Invoice sales in period",MoneyMath.money(invoices.sumOf { it.total }))
    Metric("Money received in period",MoneyMath.money(payments.sumOf { it.amount }))
    Metric("Current credit on invoices issued in period",MoneyMath.money(invoices.sumOf { it.due }))
    Metric("Projects completed in period",projects.size.toString())
    Picker("Export",listOf("Sales","Payments","Invoice credit","Client credit","Stock","Projects","Movements","Activity").map { it to it },kind,{ kind=it })
    if(kind=="Stock") Text("Stock export is the current balance of every product in every warehouse; dates do not apply.")
    if(kind=="Client credit") Text("Client credit export is today's outstanding balance per client; dates do not apply.")
    Button(onClick={ checked(model) { model.csvContent=reportCsv(kind,data,activeFrom,activeTo); csv.launch("${kind.lowercase().replace(' ','_')}_${today()}.csv") } },enabled=!model.busy,modifier=Modifier.fillMaxWidth()) { Text("Save CSV report") }
    Text("Profit is not calculated because purchase costs are not recorded in this version.")
}
