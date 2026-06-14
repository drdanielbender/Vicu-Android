package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.rendyhd.vicu.auth.OidcHandler
import platform.WebKit.*
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject
import platform.objc.sel_registerName
import kotlinx.cinterop.*

@Composable
actual fun rememberOidcLauncher(
    onResult: (code: String?, state: String?, error: String?) -> Unit
): (OidcHandler.AuthParams) -> Unit {
    val onResultState = rememberUpdatedState(onResult)

    return { params ->
        val webViewController = UIViewController()
        
        val webView = WKWebView().apply {
            navigationDelegate = OidcNavigationDelegate(
                redirectPrefix = params.redirectUri,
                expectedState = params.state,
                onComplete = { code, state, error ->
                    onResultState.value(code, state, error)
                    webViewController.dismissViewControllerAnimated(true, null)
                }
            )
        }
        
        webViewController.view = webView
        
        val nsUrl = NSURL(string = params.authUrl)
        val request = NSURLRequest(uRL = nsUrl)
        webView.loadRequest(request)

        val navController = UINavigationController(rootViewController = webViewController)
        
        val cancelButton = UIBarButtonItem(
            title = "Cancel",
            style = UIBarButtonItemStyle.UIBarButtonItemStylePlain,
            target = OidcCancelTarget {
                onResultState.value(null, null, "Cancelled")
                navController.dismissViewControllerAnimated(true, null)
            },
            action = sel_registerName("onCancel")
        )
        webViewController.navigationItem.leftBarButtonItem = cancelButton
        webViewController.title = "Sign In"

        val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow?.let { it?.rootViewController }
        
        rootVC?.presentViewController(navController, animated = true, completion = null)
    }
}

private class OidcCancelTarget(private val onCancel: () -> Unit) : NSObject() {
    @ObjCAction
    fun onCancel() {
        onCancel.invoke()
    }
}

private class OidcNavigationDelegate(
    private val redirectPrefix: String,
    private val expectedState: String,
    private val onComplete: (code: String?, state: String?, error: String?) -> Unit
) : NSObject(), WKNavigationDelegateProtocol {

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString ?: ""
        if (url.startsWith(redirectPrefix)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            
            val components = NSURLComponents(string = url, resolveAgainstBaseURL = false)
            val queryItems = components?.queryItems
            
            var code: String? = null
            var state: String? = null
            var error: String? = null
            
            queryItems?.forEach { item ->
                if (item is NSURLQueryItem) {
                    when (item.name) {
                        "code" -> code = item.value
                        "state" -> state = item.value
                        "error" -> error = item.value
                    }
                }
            }
            
            if (state == expectedState) {
                onComplete(code, state, error)
            } else {
                onComplete(null, null, "State mismatch")
            }
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
    }
}
