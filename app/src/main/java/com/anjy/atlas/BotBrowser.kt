package com.anjy.atlas

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * The bot's own web browser on the phone: a real WebView that Atlas Bot drives in the background (open pages, read
 * them, click, type, pick, press keys, scroll, go back, download), so a bot can *do* things on websites instead of
 * only reading them. It keeps its sign-ins (cookies) between jobs, like a normal browser.
 *
 * How it runs without a screen: the WebView is made on the main thread inside Atlas's process and given a phone-sized
 * layout by hand (measure + layout), without being attached to any window. Pages load and their scripts run like
 * that; the bot works from what the page script reports (text, numbered buttons/links/fields), not from pixels.
 * When the user opens [BotBrowserActivity] to watch or take over, the same WebView moves into that screen (its context
 * is swapped to the activity through a [MutableContextWrapper], so pop-ups like dropdowns work), and back afterwards.
 * While it's on screen, clicks are real touches; off screen they're the page events a click makes.
 *
 * One bot browser at a time (a WebView is heavy on a phone). It belongs to one job, is closed when that job ends, after
 * a while unused, or when Android is short of memory. Everything here runs on the main thread, never blocking it:
 * waits are suspensions.
 */
@SuppressLint("SetJavaScriptEnabled")
object BotBrowser {
    private const val TAG = "AtlasBotBrowser"
    private const val W_DP = 412
    private const val H_DP = 915
    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val IDLE_CLOSE_MS = 10 * 60_000L
    const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ctxWrap: MutableContextWrapper? = null
    private lateinit var app: Context
    /** The job the browser belongs to now. */
    var owner: String? = null; private set
    private var lastUse = 0L
    private var loading = false
    private var navs = 0
    /** Set while the user is watching or has taken over in [BotBrowserActivity]. */
    @Volatile var onScreen = false; private set
    /** Downloads the page started by itself (a click on a download link), waiting to be reported to the bot. */
    private val started = mutableListOf<String>()
    private const val MAX_PAGE_DOWNLOADS_AT_ONCE = 3
    private const val MAX_PAGE_DOWNLOADS = 20
    /** Downloads pages started by themselves since the browser opened. */
    private var pageDownloads = 0
    private var callbacks: ComponentCallbacks2? = null
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { if (l !in listeners) listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.toList().forEach { try { it() } catch (_: Exception) {} }

    class Out(val text: String, val step: String, val url: String? = null, val waitForUser: String? = null)

    fun live(jobId: String?) = jobId != null && owner == jobId && web != null
    fun view(): WebView? = web
    fun currentUrl(): String? = web?.url

    // ---------------------------------------------------------------- the WebView's life

    private fun ensure(ctx: Context, jobId: String): WebView? {
        app = ctx.applicationContext
        val w = web
        if (w != null) {
            if (owner != jobId && owner != null && Bots.job(owner)?.let { !it.final } == true) return null
            owner = jobId; lastUse = SystemClock.elapsedRealtime(); return w
        }
        val wrap = MutableContextWrapper(app)
        val v = WebView(wrap)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)        // links that open a new window open here instead
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = UA
            builtInZoomControls = true; displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true; navs++; changed() }
            override fun onPageFinished(view: WebView?, url: String?) { loading = false; CookieManager.getInstance().flush(); changed() }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // only web pages: an intent:, market: or tel: link from a page must not reach into the phone
                val u = request?.url?.toString() ?: return true
                return !(u.startsWith("https://", true) || u.startsWith("http://", true) || u.startsWith("about:", true))
            }
        }
        v.setDownloadListener { url, _, cd, mime, _ ->
            val j = owner ?: return@setDownloadListener
            // a page can start downloads by itself, again and again: a few at a time and a limit per page session, or it could fill the phone
            val skip = synchronized(started) {
                val going = started.count { !it.startsWith("done:") }
                when {
                    going >= MAX_PAGE_DOWNLOADS_AT_ONCE -> "Skipped a download the page started by itself: $going are still downloading."
                    pageDownloads >= MAX_PAGE_DOWNLOADS -> "Skipped a download the page started by itself: it has started $pageDownloads already."
                    else -> { pageDownloads++; started.add(url); null }
                }
            }
            if (skip != null) { synchronized(started) { if ("done:$skip" !in started) started.add("done:$skip") }; return@setDownloadListener }
            Bots.scopeLaunch { val r = saveDownload(j, url, BrowserRules.nameFromDisposition(cd), mime); synchronized(started) { started.remove(url); started.add("done:$r") } }
        }
        layOut(v)
        web = v; ctxWrap = wrap; owner = jobId; lastUse = SystemClock.elapsedRealtime(); loading = false
        if (callbacks == null) {
            val cb = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    // short of memory: close the browser unless a bot is using it right now and the phone isn't in trouble
                    val busy = owner?.let { Bots.job(it)?.state == BotRules.RUNNING } == true
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && (!busy || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) && !onScreen) close("Android was short of memory")
                    else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !busy && !onScreen && !waitingForUser()) close("Android was short of memory")
                }
                override fun onConfigurationChanged(newConfig: Configuration) {}
                @Deprecated("Deprecated in Java") override fun onLowMemory() { if (!onScreen) close("Android was short of memory") }
            }
            app.registerComponentCallbacks(cb); callbacks = cb
        }
        main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, IDLE_CLOSE_MS)
        changed()
        return v
    }

    /** Gives the off-screen WebView a phone-sized layout, so pages lay out and element positions are real. */
    private fun layOut(v: WebView) {
        val d = app.resources.displayMetrics.density
        val w = (W_DP * d).toInt(); val h = (H_DP * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /** A job waiting for the user's OK or answer keeps its page open longer: closing it would undo an approved click. */
    private const val WAITING_CLOSE_MS = 2 * 3_600_000L
    private fun waitingForUser(): Boolean = owner?.let { Bots.job(it)?.state }.let { it == BotRules.AWAITING_APPROVAL || it == BotRules.AWAITING_ANSWER }

    private val idleCheck = object : Runnable {
        override fun run() {
            val busy = owner?.let { Bots.job(it)?.state == BotRules.RUNNING } == true
            if (web == null) return
            val limit = if (waitingForUser()) WAITING_CLOSE_MS else IDLE_CLOSE_MS
            if (!busy && !onScreen && SystemClock.elapsedRealtime() - lastUse >= limit) close("unused for a while")
            else main.postDelayed(this, 60_000)
        }
    }

    /** Closes the browser (sign-ins stay: cookies are kept by Android). Main thread. */
    fun close(why: String) {
        val v = web ?: return
        Log.i(TAG, "closing: $why")
        web = null; owner = null; loading = false; synchronized(started) { pageDownloads = 0 }
        try { (v.parent as? android.view.ViewGroup)?.removeView(v); v.stopLoading(); v.loadUrl("about:blank"); v.removeAllViews(); v.destroy() } catch (e: Exception) { Log.w(TAG, "close", e) }
        ctxWrap = null
        main.removeCallbacks(idleCheck)
        changed()
    }

    /** A job ended: its browser goes. */
    fun release(jobId: String) { if (owner == jobId && !onScreen) close("job ended") }

    // ---------------------------------------------------------------- watching / taking over

    /** The WebView moves into [activity]'s screen. Returns null when there's no bot browser open. */
    fun attach(activity: Context): WebView? {
        val v = web ?: return null
        ctxWrap?.baseContext = activity
        onScreen = true; lastUse = SystemClock.elapsedRealtime(); changed()
        return v
    }

    /** Back off screen after watching. */
    fun detach() {
        val v = web
        onScreen = false
        if (v != null) {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            ctxWrap?.baseContext = app
            layOut(v)
        }
        lastUse = SystemClock.elapsedRealtime()
        changed()
        // the job was stopped (or finished) while the user watched: release() had to leave it, so it goes now, not 10 minutes later
        owner.let { o -> if (o == null || Bots.job(o)?.final != false) close("job ended") }
    }

    // ---------------------------------------------------------------- the page script

    private fun js(v: WebView, code: String, timeoutMs: Long = 8_000): suspend () -> String? = {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont -> v.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(r) } }
        }
    }

    /** Runs [code] in the page and returns its JSON result (the page script returns a JSON string). */
    private suspend fun eval(v: WebView, code: String): JSONObject? {
        val raw = js(v, code)() ?: return null
        return try {
            val s = if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as? String ?: return null else raw
            if (s == "null") null else JSONObject(s)
        } catch (e: Exception) { Log.w(TAG, "page script answer unreadable", e); null }
    }

    /** Tags what can be used on the page with numbers and reports it with the readable text. */
    private val READ_JS = """
(function(){
 try {
  var max=${BrowserRules.MAX_ELEMENTS};
  var old=document.querySelectorAll('[data-atlas-id]'); for(var i=0;i<old.length;i++) old[i].removeAttribute('data-atlas-id');
  var sel='a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=switch],[role=tab],[role=menuitem],[role=option],[role=combobox],[contenteditable=""],[contenteditable=true],[onclick]';
  var all=document.querySelectorAll(sel), out=[], n=0, more=0, seen=new Set();
  function vis(e){ var r=e.getBoundingClientRect(); if(r.width<2||r.height<2) return false; var s=getComputedStyle(e); return s.visibility!='hidden'&&s.display!='none'&&parseFloat(s.opacity||'1')>0.05; }
  function lab(e){
    var t=e.getAttribute('aria-label')||'';
    if(!t && e.id){ var l=document.querySelector('label[for="'+CSS.escape(e.id)+'"]'); if(l) t=l.innerText; }
    if(!t && e.labels && e.labels.length) t=e.labels[0].innerText;
    if(!t) t=(e.innerText||'');
    if(!t) t=e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.getAttribute('name')||'';
    if(!t){ var im=e.querySelector&&e.querySelector('img[alt]'); if(im) t=im.getAttribute('alt'); }
    if(!t && (e.tagName=='INPUT') && (e.type=='submit'||e.type=='button')) t=e.value||'';
    return (t||'').replace(/\s+/g,' ').trim().slice(0,120);
  }
  function kind(e){
    var tg=e.tagName.toLowerCase(), r=e.getAttribute('role'), ty=(e.getAttribute('type')||'').toLowerCase();
    if(tg=='a') return 'link'; if(tg=='button'||r=='button') return 'button'; if(tg=='select'||r=='combobox') return 'dropdown';
    if(tg=='textarea'||e.isContentEditable) return 'text box';
    if(tg=='input'){ if(ty=='checkbox') return 'checkbox'; if(ty=='radio') return 'option'; if(ty=='submit'||ty=='button'||ty=='image'||ty=='reset') return 'button'; if(ty=='password') return 'password field'; if(ty=='file') return 'file picker'; return (ty&&ty!='text'?ty+' ':'')+'field'; }
    if(r) return r; return tg=='summary'?'expander':'element';
  }
  for(var i=0;i<all.length;i++){
    var e=all[i]; if(seen.has(e)||!vis(e)) continue; seen.add(e);
    if(n>=max){ more++; continue; }
    n++; e.setAttribute('data-atlas-id',String(n));
    var o={n:n,kind:kind(e),label:lab(e),disabled:!!e.disabled||e.getAttribute('aria-disabled')=='true',inForm:!!e.form};
    if(e.tagName=='A') o.href=e.getAttribute('href');
    if(e.tagName=='INPUT'||e.tagName=='TEXTAREA'){ var ty=(e.type||'').toLowerCase(); if(ty=='checkbox'||ty=='radio') o.checked=!!e.checked; else if(ty!='password'&&ty!='submit'&&ty!='button') o.value=String(e.value||'').slice(0,80); }
    if(e.tagName=='SELECT'){ var op=e.options[e.selectedIndex]; o.value=op?op.text:''; }
    out.push(o);
  }
  var pw=!!document.querySelector('input[type=password]');
  var cap=!!document.querySelector('iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="challenges.cloudflare"],.g-recaptcha,.h-captcha,.cf-turnstile,#challenge-form');
  var txt=(document.body?document.body.innerText:'')||'';
  return JSON.stringify({title:document.title,url:location.href,elements:out,more:more,password:pw,captcha:cap,text:txt.slice(0,20000),ready:document.readyState});
 } catch(err){ return JSON.stringify({title:document.title,url:location.href,elements:[],text:'',error:String(err)}); }
})()
""".trimIndent()

    /** Finds an element by number or words, scrolls it into view and reports where it is and what it says. */
    private fun findJs(target: String) = """
(function(t){
  var e=null, num=parseInt(String(t).replace(/[\[\]\s]/g,''),10);
  if(String(t).trim().match(/^\[?\d+\]?$/)) e=document.querySelector('[data-atlas-id="'+num+'"]');
  else {
    var want=String(t).trim().toLowerCase(), all=document.querySelectorAll('[data-atlas-id]'), part=null;
    for(var i=0;i<all.length;i++){ var a=all[i], l=((a.getAttribute('aria-label')||a.innerText||a.value||a.getAttribute('placeholder')||'')+'').replace(/\s+/g,' ').trim().toLowerCase();
      if(l==want){ e=a; break; } if(!part && l.indexOf(want)>=0) part=a; }
    if(!e) e=part;
  }
  if(!e) return JSON.stringify({found:false});
  e.scrollIntoView({block:'center',inline:'center'});
  var r=e.getBoundingClientRect();
  var lab=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim().slice(0,120);
  return JSON.stringify({found:true,id:e.getAttribute('data-atlas-id'),vw:window.innerWidth,x:r.left+r.width/2,y:r.top+r.height/2,label:lab,tag:e.tagName,type:(e.type||'').toLowerCase(),inForm:!!e.form,href:e.tagName=='A'?e.href:null,password:(e.type||'').toLowerCase()=='password'});
})(${JSONObject.quote(target)})
""".trimIndent()

    private fun clickJs(id: String) = """
(function(id){
  var e=document.querySelector('[data-atlas-id="'+id+'"]'); if(!e) return JSON.stringify({ok:false});
  var r=e.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2, o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};
  try{ e.dispatchEvent(new PointerEvent('pointerdown',o)); }catch(_){}
  e.dispatchEvent(new MouseEvent('mousedown',o)); if(e.focus) e.focus();
  try{ e.dispatchEvent(new PointerEvent('pointerup',o)); }catch(_){}
  e.dispatchEvent(new MouseEvent('mouseup',o)); e.click();
  return JSON.stringify({ok:true});
})(${JSONObject.quote(id)})
""".trimIndent()

    private fun typeJs(id: String, text: String, submit: Boolean) = """
(function(id,text,submit){
  var e=document.querySelector('[data-atlas-id="'+id+'"]'); if(!e) return JSON.stringify({ok:false,why:'gone'});
  if((e.type||'').toLowerCase()=='password') return JSON.stringify({ok:false,why:'password'});
  e.focus();
  if(e.isContentEditable){ document.execCommand('selectAll',false,null); document.execCommand('insertText',false,text); }
  else if(e.tagName=='INPUT'||e.tagName=='TEXTAREA'){
    var proto=e.tagName=='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
    var set=Object.getOwnPropertyDescriptor(proto,'value').set; set.call(e,text);
    e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
  } else return JSON.stringify({ok:false,why:'not a field'});
  if(submit){
    var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
    var go=e.dispatchEvent(new KeyboardEvent('keydown',k)); e.dispatchEvent(new KeyboardEvent('keypress',k)); e.dispatchEvent(new KeyboardEvent('keyup',k));
    if(go && e.form){ if(e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
  }
  return JSON.stringify({ok:true,value:String(e.value||e.innerText||'').slice(0,80)});
})(${JSONObject.quote(id)},${JSONObject.quote(text)},$submit)
""".trimIndent()

    private fun selectJs(id: String, choice: String) = """
(function(id,c){
  var e=document.querySelector('[data-atlas-id="'+id+'"]'); if(!e) return JSON.stringify({ok:false,why:'gone'});
  if(e.tagName!='SELECT') return JSON.stringify({ok:false,why:'not a dropdown'});
  var w=String(c).trim().toLowerCase(), hit=-1, opts=[];
  for(var i=0;i<e.options.length;i++){ var o=e.options[i], t=(o.text||'').trim().toLowerCase(); opts.push(o.text.trim()); if(hit<0&&(t==w||o.value.toLowerCase()==w)) hit=i; }
  if(hit<0) for(var i=0;i<e.options.length;i++){ if(e.options[i].text.toLowerCase().indexOf(w)>=0){ hit=i; break; } }
  if(hit<0) return JSON.stringify({ok:false,why:'no such option',options:opts.slice(0,30)});
  e.selectedIndex=hit; e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
  return JSON.stringify({ok:true,value:e.options[hit].text});
})(${JSONObject.quote(id)},${JSONObject.quote(choice)})
""".trimIndent()

    private fun keyJs(key: String) = """
(function(key){
  var e=document.activeElement||document.body, codes={Enter:13,Tab:9,Escape:27,Backspace:8,ArrowDown:40,ArrowUp:38,ArrowLeft:37,ArrowRight:39,Space:32};
  var k={key:key==='Space'?' ':key,code:key,keyCode:codes[key]||0,which:codes[key]||0,bubbles:true,cancelable:true};
  var go=e.dispatchEvent(new KeyboardEvent('keydown',k)); e.dispatchEvent(new KeyboardEvent('keyup',k));
  if(key==='Enter' && go && e.form){ if(e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
  return JSON.stringify({ok:true,inForm:!!e.form,label:((e.getAttribute&&e.getAttribute('aria-label'))||e.value||'').slice(0,60)});
})(${JSONObject.quote(key)})
""".trimIndent()

    private const val FOCUS_JS = "(function(){var e=document.activeElement;return JSON.stringify({inForm:!!(e&&e.form),label:e?((e.getAttribute&&e.getAttribute('aria-label'))||e.getAttribute&&e.getAttribute('placeholder')||'').slice(0,60):''});})()"

    // ---------------------------------------------------------------- waiting for pages

    /** After an action: give the page a moment, wait for any load it started, then let it settle. */
    private suspend fun settle(v: WebView, before: Int) {
        delay(350)
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < LOAD_TIMEOUT_MS) {
            val navigated = navs != before
            if (!loading) {
                val ready = eval(v, "JSON.stringify({r:document.readyState})")?.optString("r")
                if (ready == "complete" || (ready == "interactive" && !navigated)) break
            }
            delay(250)
        }
        delay(if (navs != before) 700 else 300)
    }

    private suspend fun read(v: WebView, chars: Int): String {
        val page = eval(v, READ_JS) ?: return "Couldn't read this page (it didn't answer). Try browser_wait, then browser_read."
        return BrowserRules.pageText(page, chars) + downloadsNote()
    }

    private fun downloadsNote(): String {
        val done = synchronized(started) { started.filter { it.startsWith("done:") }.also { started.removeAll(it) } }
        val going = synchronized(started) { started.count { !it.startsWith("done:") } }
        if (done.isEmpty() && going == 0) return ""
        return buildString {
            append("\n\nDownloads:")
            done.forEach { append("\n- ").append(it.removePrefix("done:")) }
            if (going > 0) append("\n- $going still downloading")
        }
    }

    // ---------------------------------------------------------------- what the bot can do

    /** Words for the job page and the "doing it now" mark. */
    fun label(name: String, args: JSONObject): String = when (name) {
        "browser_open" -> "Opening ${args.optString("url").removePrefix("https://").removePrefix("http://").take(50)}"
        "browser_read" -> "Reading the page"
        "browser_click" -> "Clicking “${args.optString("target").take(40)}”"
        "browser_type" -> "Typing into a field"
        "browser_select" -> "Choosing “${args.optString("option").take(40)}”"
        "browser_press" -> "Pressing ${args.optString("key")}"
        "browser_scroll" -> "Scrolling the page"
        "browser_back" -> "Going back"
        "browser_wait" -> "Waiting for the page"
        "browser_download" -> "Downloading a file"
        "browser_fill_login" -> "Filling in a saved sign-in"
        else -> "Using the browser"
    }

    /** What the target element says and whether it's in a form, for the approval check before an action. */
    suspend fun inspect(ctx: Context, jobId: String, name: String, args: JSONObject): Pair<String?, Boolean> {
        val v = if (live(jobId)) web!! else return null to false
        return when (name) {
            "browser_click", "browser_type" -> eval(v, findJs(args.optString("target")))?.takeIf { it.optBoolean("found") }?.let { it.optString("label") to it.optBoolean("inForm") } ?: (null to false)
            "browser_press" -> eval(v, FOCUS_JS)?.let { it.optString("label").ifBlank { null } to it.optBoolean("inForm") } ?: (null to false)
            else -> null to false
        }
    }

    fun site(): String = web?.url?.let { BrowserRules.host(it) } ?: ""

    /**
     * What the page says around the action (BrowserRules.Signals): its address, whether it has payment or card fields,
     * the target element's hidden words and its form. Read right before a risky-looking action, for the money rule.
     */
    suspend fun signals(ctx: Context, jobId: String, name: String, args: JSONObject): BrowserRules.Signals {
        val v = if (live(jobId)) web!! else return BrowserRules.Signals()
        val id = when (name) {
            "browser_click", "browser_type", "browser_select" -> eval(v, findJs(args.optString("target")))?.takeIf { it.optBoolean("found") }?.optString("id")
            else -> null
        }
        // no answer (the script failed or timed out): it can't be shown there's no money involved, so it counts as if there were
        val o = eval(v, signalsJs(id, focused = name == "browser_press")) ?: return BrowserRules.Signals(url = v.url ?: "", unread = true)
        return BrowserRules.Signals(o.optString("url", v.url ?: ""), o.optBoolean("pay"), o.optString("el"), o.optString("form"), unread = o.optBoolean("unread"))
    }

    private fun signalsJs(id: String?, focused: Boolean) = """
(function(id,focused){
 try {
  function attr(x,n){ return (x&&x.getAttribute&&x.getAttribute(n))||''; }
  function words(x){ if(!x) return ''; var t=[attr(x,'aria-label'),attr(x,'title'),attr(x,'name'),x.id||'',attr(x,'data-testid'),attr(x,'formaction')];
    var ty=(x.type||'').toLowerCase(); if(x.tagName=='BUTTON'||ty=='submit'||ty=='button'||ty=='image') t.push(x.value||'', x.innerText||'');
    return t.join(' ').replace(/\s+/g,' ').slice(0,400); }
  var e=id?document.querySelector('[data-atlas-id="'+id+'"]'):(focused?document.activeElement:null);
  var form=e&&(e.form||(e.closest&&e.closest('form')));
  var ft='';
  if(form){ ft=attr(form,'action')+' '+attr(form,'aria-label')+' '+attr(form,'name')+' '+(form.id||'');
    var subs=form.querySelectorAll('button:not([type]),button[type=submit],input[type=submit],input[type=image]');
    for(var i=0;i<subs.length&&i<6;i++) ft+=' '+words(subs[i]); }
  var pay=false, rx=/card.?num|cc-?(num|number|csc|exp)|\bcvv|\bcvc|\bcsc\b|security.?code|expir|\biban\b|payment|checkout|\bpay\b/i;
  var ins=document.querySelectorAll('input,select');
  for(var j=0;j<ins.length&&!pay;j++){ var x=ins[j], ac=(attr(x,'autocomplete')||'').toLowerCase();
    if(ac.indexOf('cc-')==0) pay=true;
    else { var lab=''; if(x.id){ var l=null; try{ l=document.querySelector('label[for="'+CSS.escape(x.id)+'"]'); }catch(_){} if(l) lab=l.innerText||''; }
      if(rx.test(attr(x,'name')+' '+(x.id||'')+' '+attr(x,'placeholder')+' '+attr(x,'aria-label')+' '+lab)) pay=true; } }
  if(!pay){ var fr=document.querySelectorAll('iframe'); for(var k=0;k<fr.length;k++){ if(/stripe|braintree|adyen|paypal|klarna|checkout|payment|card/i.test((fr[k].src||'')+' '+attr(fr[k],'name')+' '+attr(fr[k],'title'))){ pay=true; break; } } }
  return JSON.stringify({url:location.href,pay:pay,el:words(e),form:ft.replace(/\s+/g,' ').slice(0,600)});
 } catch(err){ return JSON.stringify({url:location.href,unread:true}); }
})(${if (id == null) "null" else JSONObject.quote(id)},$focused)
""".trimIndent()

    /** Runs one browser tool for job [jobId]. Main thread (suspends while pages load). */
    suspend fun run(ctx: Context, jobId: String, name: String, args: JSONObject): Out {
        if (name != "browser_open" && !live(jobId)) {
            if (owner != null && owner != jobId) return Out("Error: the bot browser is busy with another job. Try again later or do this another way.", "Browser busy")
            return Out("Error: no page is open. Use browser_open with an address first.", "No page open")
        }
        val v = ensure(ctx, jobId) ?: return Out("Error: the bot browser is busy with another job. Try again later or do this another way.", "Browser busy")
        lastUse = SystemClock.elapsedRealtime()
        val before = navs
        return when (name) {
            "browser_open" -> {
                val url = BrowserRules.normalizeUrl(args.optString("url")) ?: return Out("Error: that isn't a web address I can open (only http/https pages).", "Couldn't open that address")
                v.loadUrl(url)
                settle(v, before)
                Out(read(v, BrowserRules.ACTION_CHARS), "Opened ${BrowserRules.host(v.url ?: url)}", url = v.url ?: url)
            }
            "browser_read" -> Out(read(v, BrowserRules.READ_CHARS), "Read ${site().ifBlank { "the page" }}")
            // ---- a saved sign-in from the vault (Vault.kt): the values go into the page, never to the model
            "browser_fill_login" -> {
                val ref = args.optString("entry")
                val e = Vault.find(withContext(Dispatchers.IO) { Vault.all(ctx) }, ref)
                    ?: return Out("Error: there's no saved sign-in called “${ref.take(40)}” (or more than one fits). Use vault_list for the names, or ask the user to sign in.", "No such saved sign-in")
                if (!Vault.siteMatches(e.site, site())) return Out("Error: the saved sign-in “${e.name}” is for ${e.site}, but this page is ${site().ifBlank { "not a website" }}. Open ${e.site}'s sign-in page first.", "Wrong site for that sign-in")
                val r = eval(v, Vault.fillJs(e.username, e.password))
                if (r?.optBoolean("ok") != true) return Out("Error: couldn't find the sign-in fields on this page. Open the sign-in page (it may be a step further, e.g. after entering the username).", "No sign-in fields here")
                delay(200)
                Out("Filled the saved sign-in “${e.name}”: username ${if (r.optBoolean("user")) "filled" else "not found"}, password ${if (r.optBoolean("pass")) "filled" else "not found (it may be on the next step)"}. Now click the sign-in / next button.\n\n" + read(v, BrowserRules.ACTION_CHARS),
                    "Filled your saved sign-in for ${e.site}")
            }
            "browser_click" -> {
                val target = args.optString("target")
                val f = eval(v, findJs(target))
                if (f == null || !f.optBoolean("found")) return Out("Error: “${target.take(60)}” isn't on the page (numbers change after every action). Read the page again and use a number from the latest list.", "Couldn't find “${target.take(30)}”")
                if (f.optBoolean("password")) return Out("That's a password field: the user signs in themselves. Use ask_user to ask them to sign in in the bot's browser.", "Sign-in needed", waitForUser = "Please sign in on ${site()} in the bot's browser, then tap Hand Back.")
                delay(150) // let the scroll land
                val id = f.optString("id")
                val pos = eval(v, findJs(id)) ?: f
                if (onScreen && v.isAttachedToWindow) touch(v, pos.optDouble("x").toFloat(), pos.optDouble("y").toFloat(), pos.optDouble("vw", 0.0).toFloat()) else eval(v, clickJs(id))
                settle(v, before)
                Out("Clicked “${f.optString("label").take(60)}”.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Clicked “${f.optString("label").take(40)}”")
            }
            "browser_type" -> {
                val target = args.optString("target"); val text = args.optString("text")
                val f = eval(v, findJs(target))
                if (f == null || !f.optBoolean("found")) return Out("Error: “${target.take(60)}” isn't on the page. Read the page again and use a number from the latest list.", "Couldn't find the field")
                if (f.optBoolean("password")) return Out("That's a password field: never type passwords. Use ask_user to ask the user to sign in in the bot's browser.", "Sign-in needed", waitForUser = "Please sign in on ${site()} in the bot's browser, then tap Hand Back.")
                val r = eval(v, typeJs(f.optString("id"), text, args.optBoolean("submit", false)))
                if (r?.optBoolean("ok") != true) return Out("Error: couldn't type there (${r?.optString("why") ?: "no answer"}). Pick a text field from the list.", "Couldn't type there")
                settle(v, before)
                Out("Typed into “${f.optString("label").take(40)}”${if (args.optBoolean("submit")) " and submitted" else ""}.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Typed into “${f.optString("label").take(30)}”")
            }
            "browser_select" -> {
                val target = args.optString("target")
                val f = eval(v, findJs(target))
                if (f == null || !f.optBoolean("found")) return Out("Error: “${target.take(60)}” isn't on the page. Read the page again.", "Couldn't find the dropdown")
                val r = eval(v, selectJs(f.optString("id"), args.optString("option")))
                if (r?.optBoolean("ok") != true) {
                    val opts = r?.optJSONArray("options")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }
                    return Out("Error: ${r?.optString("why") ?: "couldn't choose that"}.${opts?.let { " The options are: $it" } ?: ""}", "Couldn't choose that")
                }
                settle(v, before)
                Out("Chose “${r.optString("value")}”.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Chose “${r.optString("value").take(30)}”")
            }
            "browser_press" -> {
                val key = when (args.optString("key").trim().lowercase()) { "enter", "return" -> "Enter"; "tab" -> "Tab"; "escape", "esc" -> "Escape"; "backspace" -> "Backspace"; "down" -> "ArrowDown"; "up" -> "ArrowUp"; "left" -> "ArrowLeft"; "right" -> "ArrowRight"; "space" -> "Space"; else -> null }
                    ?: return Out("Error: I can press Enter, Tab, Escape, Backspace, Space, Up, Down, Left or Right.", "Unknown key")
                if (onScreen && v.isAttachedToWindow) pressKey(v, key) else eval(v, keyJs(key))
                settle(v, before)
                Out("Pressed $key.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Pressed $key")
            }
            "browser_scroll" -> {
                val dir = args.optString("direction", "down").lowercase()
                val code = when (dir) { "up" -> "window.scrollBy(0,-innerHeight*0.8)"; "top" -> "window.scrollTo(0,0)"; "bottom" -> "window.scrollTo(0,document.body.scrollHeight)"; else -> "window.scrollBy(0,innerHeight*0.8)" }
                js(v, "$code;'ok'")()
                delay(400)
                Out("Scrolled $dir.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Scrolled $dir")
            }
            "browser_back" -> {
                if (!v.canGoBack()) return Out("There's no earlier page to go back to.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Nothing to go back to")
                v.goBack(); settle(v, before)
                Out("Went back.\n\n" + read(v, BrowserRules.ACTION_CHARS), "Went back")
            }
            "browser_wait" -> {
                delay((args.optDouble("seconds", 3.0).coerceIn(1.0, 15.0) * 1000).toLong())
                settle(v, before)
                Out(read(v, BrowserRules.ACTION_CHARS), "Waited for the page")
            }
            "browser_download" -> {
                val target = args.optString("target").trim()
                val url = if (target.toIntOrNull() != null || target.startsWith("[")) eval(v, findJs(target))?.optString("href")?.takeIf { it.isNotBlank() && it != "null" }
                    else BrowserRules.normalizeUrl(target) ?: v.url?.let { base -> try { URL(URL(base), target).toString() } catch (_: Exception) { null } }
                if (url == null || BrowserRules.normalizeUrl(url) == null) return Out("Error: give a link number from the page or a web address to download.", "Couldn't find the file")
                Out(saveDownload(jobId, url, null, null), "Downloaded a file")
            }
            else -> Out("Error: there's no browser tool called $name.", "Unknown browser tool")
        }
    }

    /** A real tap at page coordinates (CSS pixels), used while the browser is on screen. */
    private fun touch(v: WebView, x: Float, y: Float, viewportCss: Float) {
        // page (CSS) pixels to screen pixels: the page's width maps onto the view's width
        val k = if (viewportCss > 0f) v.width / viewportCss else v.resources.displayMetrics.density
        val px = x * k; val py = y * k
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, px, py, 0)
        try { v.dispatchTouchEvent(down); v.dispatchTouchEvent(up) } finally { down.recycle(); up.recycle() }
    }

    private fun pressKey(v: WebView, key: String) {
        val code = when (key) { "Enter" -> KeyEvent.KEYCODE_ENTER; "Tab" -> KeyEvent.KEYCODE_TAB; "Escape" -> KeyEvent.KEYCODE_ESCAPE; "Backspace" -> KeyEvent.KEYCODE_DEL
            "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN; "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP; "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT; "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT; else -> KeyEvent.KEYCODE_SPACE }
        v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------------------------------------------------------------- downloads

    fun jobDir(ctx: Context, jobId: String): File = File(ctx.applicationContext.filesDir, "bot/${BrowserRules.safeFileName(jobId, "job")}")
    fun files(ctx: Context, jobId: String): List<File> = jobDir(ctx, jobId).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()

    /** Fetches [url] with the browser's sign-in (cookies) into the job's folder. Returns a sentence for the bot. */
    private suspend fun saveDownload(jobId: String, url: String, name: String?, mime: String?): String = withContext(Dispatchers.IO) {
        try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true; con.connectTimeout = 20_000; con.readTimeout = 60_000
            con.setRequestProperty("User-Agent", UA)
            CookieManager.getInstance().getCookie(url)?.let { con.setRequestProperty("Cookie", it) }
            try {
                if (con.responseCode !in 200..299) return@withContext "Download failed: the site answered ${con.responseCode}."
                val len = con.contentLengthLong
                if (len > BrowserRules.MAX_DOWNLOAD_BYTES) return@withContext "Didn't download: the file is ${len / 1_048_576} MB, over the ${BrowserRules.MAX_DOWNLOAD_BYTES / 1_048_576} MB limit on the phone."
                val fname = BrowserRules.safeFileName(name ?: BrowserRules.nameFromDisposition(con.getHeaderField("Content-Disposition")) ?: url, "download")
                val dir = jobDir(app, jobId).apply { mkdirs() }
                var f = File(dir, fname); var k = 1
                while (f.exists()) { f = File(dir, fname.substringBeforeLast('.', fname) + " ($k)" + (fname.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != fname) ".$it" else "" })); k++ }
                var total = 0L
                con.inputStream.use { inp -> f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val r = inp.read(buf); if (r < 0) break; total += r
                        if (total > BrowserRules.MAX_DOWNLOAD_BYTES) { out.close(); f.delete(); return@withContext "Stopped the download: it's over the ${BrowserRules.MAX_DOWNLOAD_BYTES / 1_048_576} MB limit." }
                        out.write(buf, 0, r) }
                } }
                "Downloaded “${f.name}” (${total / 1024} KB${(mime ?: con.contentType)?.let { ", $it" } ?: ""}). It's kept with this job; the user can save it to their Downloads from the job page."
            } finally { con.disconnect() }
        } catch (e: Exception) { "Download failed: ${e.message ?: e.javaClass.simpleName}" }
    }

    /** Copies a job's file into the phone's Downloads folder (no permission needed). Returns where it went. */
    fun saveToDownloads(ctx: Context, f: File): String? = try {
        val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.RELATIVE_PATH, "Download/Atlas") }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) null else { ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }; "Download/Atlas/${f.name}" }
    } catch (e: Exception) { Log.w(TAG, "save to downloads", e); null }
}
