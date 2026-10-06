package com.example.myapplication

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

private val Navy = Color(0xFF142D42)
private val LightTeal = Color(0xFF007F78)
internal val Teal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val Paper = Color(0xFFF3F6F8)

class MainActivity : ComponentActivity() {
    private lateinit var model: PosController
    private val poller = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (model.signedIn && !model.busy) model.refresh(silent = true)
            poller.postDelayed(this, 20000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this).get(PosController::class.java)
        enableEdgeToEdge()
        setContent {
            val colors = if (model.darkMode) {
                darkColorScheme(primary = Color(0xFF78DCD1), secondary = Color(0xFFC2D9EE), background = Color(0xFF0C141B), surface = Color(0xFF18242F))
            } else {
                lightColorScheme(primary = LightTeal, secondary = Navy, background = Paper, surface = Color.White)
            }
            MaterialTheme(colorScheme = colors) {
                PosApp(model)
            }
        }
    }
    override fun onResume() { super.onResume(); poller.postDelayed(poll, 20000) }
    override fun onPause() { poller.removeCallbacks(poll); super.onPause() }
}

@Composable
private fun PosApp(model: PosController) {
    var tab by rememberSaveable { mutableStateOf("Home") }
    val pages = rememberSaveableStateHolder()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Navy, LightTeal))).padding(20.dp)) {
                Text("Warehouse POS", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (model.signedIn) "${model.data?.staffName} · Shared online records" else "Stock • Sales • Projects", color = Color(0xFFBDE6E1))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.toggleDarkMode() }) { Text(if (model.darkMode) "Light mode" else "Dark mode", color = Color.White) }
                    if (model.signedIn) {
                        TextButton(onClick = { model.refresh() }, enabled = !model.busy) { Text("Refresh", color = Color.White) }
                        TextButton(onClick = { model.logout() }, enabled = !model.busy) { Text("Sign out", color = Color.White) }
                    }
                }
            }
            if (model.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (model.error.isNotBlank()) Notice(model.error, true)
            if (model.message.isNotBlank()) Notice(model.message, false)
            if (!model.signedIn) LoginScreen(model)
            else {
                val data = model.data ?: return@Column
                if (model.hasPending) Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("An operation is awaiting confirmation. Retry uses the same ID to avoid duplicates.")
                    TextButton(onClick = { model.retry() }, enabled = !model.busy) { Text("Retry pending operation") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Home", "Stock", "Checkout", "Invoices", "Projects", "Clients", "Logs", "Reports").forEach { name ->
                        FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) })
                    }
                }
                pages.SaveableStateProvider(tab) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            "Home" -> HomeScreen(data)
                            "Stock" -> StockScreen(model, data)
                            "Checkout" -> CheckoutScreen(model, data)
                            "Invoices" -> InvoicesScreen(model, data)
                            "Projects" -> ProjectsScreen(model, data)
                            "Clients" -> ClientsScreen(model, data)
                            "Logs" -> LogsScreen(data)
                            "Reports" -> ReportsScreen(model, data)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable internal fun Notice(text: String, failure: Boolean) {
    Text(text, Modifier.fillMaxWidth().background(if (failure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        color = if (failure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
}
@Composable internal fun Heading(title: String, subtitle: String = "") {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun RecordCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}
@Composable internal fun Metric(label: String, value: String) = RecordCard(label) {
    Text(value, color = Teal, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}
@Composable internal fun Input(label: String, value: String, change: (String) -> Unit, number: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = change, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable internal fun SearchField(label: String, value: String, change: (String) -> Unit, search: () -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
        trailingIcon = { TextButton(onClick = search) { Text("Search") } })
}
@Composable internal fun Picker(label: String, options: List<Pair<String, String>>, value: String, change: (String) -> Unit,
                                selectedLabel: String? = null, emptyText: String = "No records yet") {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selectedLabel ?: options.firstOrNull { it.first == value }?.second ?: "Choose"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.second) }, onClick = { change(option.first); expanded = false }) }
            if (options.isEmpty()) DropdownMenuItem(text = { Text(emptyText) }, onClick = { expanded = false })
        }
    }
}
@Composable internal fun FormDialog(title: String, model: PosController, close: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = { if (!model.busy) close() }, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.error.isNotBlank()) Text(model.error, color = MaterialTheme.colorScheme.error)
            content()
        } },
        confirmButton = { TextButton(onClick = save, enabled = !model.busy && !model.hasPending) { Text("Save") } },
        dismissButton = { TextButton(onClick = close, enabled = !model.busy) { Text("Cancel") } })
}
internal fun checked(model: PosController, work: () -> Unit) { try { work() } catch (e: Exception) { model.localError(e.message ?: "Check the entered values.") } }
internal fun payload(vararg fields: Pair<String, Any>) = JSONObject().apply { fields.forEach { put(it.first,it.second) } }
internal fun methods() = listOf("Cash", "M-Pesa", "Bank").map { it to it }

@Composable private fun LoginScreen(model: PosController) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberMe by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Staff sign in", "Each staff member uses their own account.")
        if (!PosConfig.configured) RecordCard("Connect your company database") {
            Text("Follow SETUP.md: run the database script, create staff accounts, and set the project URL and public key in PosApi.kt.")
            Text("Business features become available after the connection is configured.")
        }
        Input("Email", email, { email = it })
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "Hide" else "Show")
                }
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it }, enabled = !model.busy)
            Text("Remember me on this phone")
        }
        Button(onClick = { model.login(email, password, rememberMe) }, enabled = PosConfig.configured && email.isNotBlank() && password.isNotBlank() && !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        if (model.hasSavedSignIn) {
            OutlinedButton(onClick = { model.restoreLogin() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Retry saved sign-in") }
            TextButton(onClick = { model.forgetSavedSignIn() }, enabled = !model.busy) { Text("Forget saved sign-in") }
        }
        Text("Records refresh every 20 seconds while this screen is active. A connection is required to record changes.")
    }
}

@Composable private fun HomeScreen(data: PosData) {
    Heading("Company overview", "All-time totals across all warehouses")
    Metric("Invoice sales", MoneyMath.money(data.invoices.sumOf { it.total }))
    Metric("Payments received", MoneyMath.money(data.payments.sumOf { it.amount }))
    Metric("Outstanding credit", MoneyMath.money(data.invoices.sumOf { it.due }))
    RecordCard("Your operation") {
        Text("${data.products.size} products · ${data.warehouses.size} warehouses")
        Text("${data.invoices.count { it.due > 0 }} outstanding invoices")
        Text("${data.projects.size} completed projects · ${data.clients.size} clients")
    }
    RecordCard("Project workflow") {
        Text("1. Receive company goods into a warehouse.")
        Text("2. Use Checkout to issue the client's goods invoice and dispatch stock.")
        Text("3. When work is complete, link the project to that invoice and record the workers, location and completion date.")
        Text("4. Record each payment against the invoice; the balance reduces automatically.")
    }
    Heading("Recent activity")
    data.audit.take(5).forEach { row -> RecordCard(row.summary) { Text("${data.actor(row.actor)} · ${displayTime(row.at)}") } }
    if (data.audit.isEmpty()) Text("Add a client and product, then receive your first goods.")
}

@Composable private fun StockScreen(model: PosController, data: PosData) {
    var warehouse by rememberSaveable { mutableStateOf(data.warehouses.firstOrNull()?.id ?: "") }
    var search by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    Heading("Warehouse stock", "Receive, dispatch or transfer goods with a movement log.")
    Picker("Warehouse", data.warehouses.map { it.id to it.name }, warehouse, { warehouse = it })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { dialog = "receive" }, enabled = !model.busy && !model.hasPending) { Text("Receive") }
        OutlinedButton(onClick = { dialog = "dispatch" }, enabled = !model.busy && !model.hasPending) { Text("Dispatch") }
        OutlinedButton(onClick = { dialog = "transfer" }, enabled = !model.busy && !model.hasPending) { Text("Transfer") }
    }
    if (data.admin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { dialog = "product" }) { Text("+ Product") }
        TextButton(onClick = { dialog = "warehouse" }) { Text("+ Warehouse") }
    }
    Input("Search name or SKU", search, { search = it })
    data.products.filter { it.name.contains(search,true) || it.sku.contains(search,true) }.forEach { product ->
        RecordCard(product.name) {
            Text("${product.sku} · ${MoneyMath.money(product.price)} / ${product.unit}")
            Text("Available: ${MoneyMath.units(data.available(product.id,warehouse))} ${product.unit}", color = Teal, fontWeight = FontWeight.Bold)
        }
    }
    if (data.products.isEmpty()) Text("An administrator must add products before stock can be received.")
    when (dialog) {
        "product" -> ProductDialog(model, data, warehouse) { dialog = "" }
        "warehouse" -> WarehouseDialog(model) { dialog = "" }
        "receive", "dispatch", "transfer" -> MovementDialog(dialog, warehouse, model, data) { dialog = "" }
    }
}

@Composable private fun ProductDialog(model: PosController, data: PosData, defaultWarehouse: String, close: () -> Unit) {
    var name by remember { mutableStateOf("") }; var sku by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("pcs") }; var price by remember { mutableStateOf("") }
    var openingQty by remember { mutableStateOf("") }; var warehouseId by remember { mutableStateOf(defaultWarehouse) }
    FormDialog("Add product",model,close,{ checked(model) {
        require(name.isNotBlank() && sku.isNotBlank()) { "Enter a product name and unique SKU." }
        val cents = MoneyMath.cents(price); require(cents > 0) { "Price must be positive." }
        val quantity = if (openingQty.isBlank() || openingQty.trim().matches(Regex("0+(\\.0{1,3})?"))) BigDecimal.ZERO else MoneyMath.quantity(openingQty)
        require(quantity.signum()==0 || data.supportsOpeningStock) { "Use Receive to enter stock, or ask your administrator to update the database first." }
        if (quantity.signum() > 0) require(warehouseId.isNotBlank()) { "Choose a warehouse for the opening stock." }
        model.command("add_product",payload("name" to name.trim(),"sku" to sku.trim(),"unit" to unit.trim(),"price_cents" to cents,
            "opening_qty" to quantity.toPlainString(),"warehouse_id" to warehouseId),close)
    } }) {
        Input("Product name",name,{ name=it }); Input("Unique SKU",sku,{ sku=it })
        Input("Unit (pcs, m, kg)",unit,{ unit=it }); Input("Selling price (KES)",price,{ price=it },true)
        if (data.supportsOpeningStock) {
            Input("Opening stock quantity (optional)",openingQty,{ openingQty=it },true)
            Picker("Opening stock warehouse",data.warehouses.map { it.id to it.name },warehouseId,{ warehouseId=it })
            Text("Enter the goods already on hand. Leave blank or zero if you will receive them later.")
        } else Text("After saving this product, use Receive to enter its warehouse quantity.")
    }
}
@Composable private fun WarehouseDialog(model: PosController, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    FormDialog("Add warehouse",model,close,{ checked(model) { require(name.isNotBlank()) { "Enter a warehouse name." }; model.command("add_warehouse",payload("name" to name.trim()),close) } }) {
        Input("Warehouse name",name,{ name=it })
    }
}
@Composable private fun MovementDialog(action: String, defaultWarehouse: String, model: PosController, data: PosData, close: () -> Unit) {
    var productId by remember { mutableStateOf("") }; var warehouseId by remember { mutableStateOf(defaultWarehouse) }
    var targetId by remember { mutableStateOf("") }; var qty by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }; var activeSearch by remember { mutableStateOf("") }
    val products = data.products.filter { it.name.contains(activeSearch,true) || it.sku.contains(activeSearch,true) }
    FormDialog("${action.replaceFirstChar { it.uppercase() }} goods",model,close,{ checked(model) {
        require(productId.isNotBlank() && warehouseId.isNotBlank()) { "Choose a product and warehouse." }
        val quantity=MoneyMath.quantity(qty); require(note.isNotBlank()) { "Add a supplier, recipient or reference." }
        if(action=="transfer") require(targetId.isNotBlank() && targetId!=warehouseId) { "Choose a different destination warehouse." }
        model.command(action,payload("product_id" to productId,"warehouse_id" to warehouseId,"target_id" to targetId,"qty" to quantity.toPlainString(),"note" to note.trim()),close)
    } }) {
        SearchField("Product name or SKU",search,{ search=it },{ activeSearch=search.trim() })
        if (search.isNotEmpty() || activeSearch.isNotEmpty()) TextButton(onClick = { search=""; activeSearch="" }) { Text("Clear search") }
        Picker("Product",products.map { it.id to "${it.sku} / ${it.name}" },productId,{ productId=it },
            selectedLabel=data.products.firstOrNull { it.id==productId }?.let { "${it.sku} / ${it.name}" }, emptyText="No products match your search")
        if (products.isEmpty()) Text("No products match your search. Try another name or SKU.")
        Picker("Warehouse",data.warehouses.map { it.id to it.name },warehouseId,{ warehouseId=it })
        if(action=="transfer") Picker("Destination",data.warehouses.map { it.id to it.name },targetId,{ targetId=it })
        if(productId.isNotBlank()) Text("Available: ${MoneyMath.units(data.available(productId,warehouseId))}")
        Input("Quantity",qty,{ qty=it },true); Input("Supplier / recipient / reference",note,{ note=it })
        if(action=="dispatch") Text("For a client sale or project, use Checkout to create a linked invoice.")
    }
}

@Composable private fun CheckoutScreen(model: PosController, data: PosData) {
    var clientId by rememberSaveable { mutableStateOf("") }; var warehouseId by rememberSaveable { mutableStateOf(data.warehouses.firstOrNull()?.id ?: "") }
    var saleType by rememberSaveable { mutableStateOf("cash") }; var paid by rememberSaveable { mutableStateOf("0") }
    var dueDate by rememberSaveable { mutableStateOf("") }; var method by rememberSaveable { mutableStateOf("Cash") }; var reference by rememberSaveable { mutableStateOf("") }
    var productId by rememberSaveable { mutableStateOf("") }; var qty by rememberSaveable { mutableStateOf("1") }
    val total = model.cart.sumOf { it.total }
    Heading("Checkout", "A completed sale creates an invoice and dispatches the goods together.")
    Picker("Client",data.clients.map { it.id to it.name },clientId,{ clientId=it })
    Picker("Warehouse",data.warehouses.map { it.id to it.name },warehouseId,{ warehouseId=it })
    Picker("Product",data.products.map { it.id to "${it.sku} / ${it.name}" },productId,{ productId=it })
    Input("Quantity",qty,{ qty=it },true)
    if(productId.isNotBlank()) Text("Warehouse stock: ${MoneyMath.units(data.available(productId,warehouseId))}")
    OutlinedButton(onClick={ checked(model) {
        val product=data.products.firstOrNull { it.id==productId } ?: throw IllegalArgumentException("Choose a product.")
        val added=MoneyMath.quantity(qty)
        val existing=model.cart.firstOrNull { it.product.id==productId }?.qty ?: BigDecimal.ZERO
        require(existing+added<=data.available(productId,warehouseId)) { "Not enough stock in this warehouse." }
        val updated=model.cart.filterNot { it.product.id==productId }+CartLine(product,existing+added)
        require(updated.all { it.total<=1000000000000L } && updated.sumOf { it.total }<=1000000000000L) { "Sale total is outside the supported range." }
        model.cart=updated
    } },enabled=!model.busy && !model.hasPending) { Text("Add to sale") }
    model.cart.forEach { line -> RecordCard(line.product.name) {
        Text("${MoneyMath.units(line.qty)} ${line.product.unit} × ${MoneyMath.money(line.product.price)} = ${MoneyMath.money(line.total)}")
        TextButton(onClick={ model.cart=model.cart.filterNot { it.product.id==line.product.id } },enabled=!model.busy) { Text("Remove") }
    } }
    Picker("Sale type",listOf("cash" to "Cash — fully paid","credit" to "Credit — pay later / part payment"),saleType,{ saleType=it })
    if(saleType=="credit") {
        Input("Initial payment (KES, 0 if unpaid)",paid,{ paid=it },true)
        Input("Due date (YYYY-MM-DD, optional)",dueDate,{ dueDate=it })
    }
    Picker("Payment method",methods(),method,{ method=it })
    Input("Payment reference (optional)",reference,{ reference=it })
    Metric("Invoice total",MoneyMath.money(total))
    Button(onClick={ checked(model) {
        require(clientId.isNotBlank() && warehouseId.isNotBlank() && model.cart.isNotEmpty()) { "Choose a client, warehouse and at least one item." }
        if(dueDate.isNotBlank()) requireDate(dueDate)
        val initial=if(saleType=="cash") total else MoneyMath.cents(paid)
        require(initial<=total) { "Initial payment exceeds the invoice total." }
        val items=JSONArray().apply { model.cart.forEach { put(payload("product_id" to it.product.id,"qty" to it.qty.toPlainString())) } }
        model.command("create_invoice",payload("client_id" to clientId,"warehouse_id" to warehouseId,"sale_type" to saleType,
            "paid_cents" to initial,"method" to method,"reference" to reference,"due_date" to if(saleType=="credit") dueDate else "","items" to items)) {
            model.cart=emptyList(); paid="0"; reference=""
        }
    } },enabled=!model.busy && !model.hasPending && model.cart.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {
        Text(if(saleType=="cash") "Confirm full payment & issue invoice" else "Issue credit invoice")
    }
}
