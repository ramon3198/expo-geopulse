package expo.modules.geopulse.core

/**
 * Sink for events leaving the SDK core. The Expo module attaches an
 * implementation that forwards to JS via `sendEvent`. Background components
 * (foreground service, receivers) only know about this interface, so they stay
 * decoupled from the React runtime and keep working when no JS listener exists.
 */
fun interface EventDispatcher {
  fun dispatch(event: String, payload: Map<String, Any?>)
}
