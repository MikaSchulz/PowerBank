// Browser telemetry for the PowerBank UI: traces, logs and metrics via OpenTelemetry.
//
// - Every user action is a root span ("send transfer", "approve transfer", …); every API call in it is a client span
//   whose W3C traceparent the backend continues. So a trace in Tempo starts with the click.
// - Uncaught errors become log records (Loki), linked to the action's trace when there is one.
// - Web vitals (LCP, INP, CLS, FCP, TTFB) become metrics (Prometheus).
// - Everything goes to the bank-service (/api/otel/v1/...), which forwards it to the collector.
//
// The OpenTelemetry library itself is vendored in vendor/otel-web.js (built from src/main/ui-telemetry). If it can't
// be loaded, the UI keeps working and only sends a plain traceparent header.

let otel = null;
let tracer = null;
let logger = null;
let actions = null;

const randomHex = (bytes) => [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, '0')).join('');

/** A W3C traceparent without a span behind it: used for polling (not sampled) and when telemetry is off. */
function plainTraceparent(sampled) {
  let traceId = randomHex(16);
  while (/^0+$/.test(traceId)) traceId = randomHex(16);
  return `00-${traceId}-${randomHex(8)}-${sampled ? '01' : '00'}`;
}

export async function initTelemetry({ enabled = true, endpoint = '/api/otel/v1', serviceVersion = 'dev' } = {}) {
  if (!enabled) return false;
  try {
    otel = await import('./vendor/otel-web.js');
    const resource = otel.resourceFromAttributes({ 'service.name': 'bank-ui', 'service.version': serviceVersion });

    const tracerProvider = new otel.TracerProvider({
      resource,
      spanProcessors: [new otel.BatchSpanProcessor({ exporter: new otel.OTLPTraceExporter({ url: `${endpoint}/traces` }), scheduledDelayMillis: 1000 })],
    });
    otel.trace.setGlobalTracerProvider(tracerProvider);
    otel.context.setGlobalContextManager(new otel.StackContextManager().enable());
    otel.propagation.setGlobalPropagator(new otel.W3CTraceContextPropagator());
    tracer = otel.trace.getTracer('bank-ui');

    const loggerProvider = new otel.LoggerProvider({
      resource,
      processors: [new otel.BatchLogRecordProcessor({ exporter: new otel.OTLPLogExporter({ url: `${endpoint}/logs` }) })],
    });
    logger = loggerProvider.getLogger('bank-ui');

    const meterProvider = new otel.MeterProvider({
      resource,
      readers: [new otel.PeriodicExportingMetricReader({ exporter: new otel.OTLPMetricExporter({ url: `${endpoint}/metrics` }), exportIntervalMillis: 10_000 })],
    });
    const meter = meterProvider.getMeter('bank-ui');
    actions = meter.createCounter('ui.actions', { description: 'User actions in the UI, by action and outcome' });
    recordWebVitals(meter);

    // The page load itself as a trace (document fetch, resource timings).
    otel.registerInstrumentations({ tracerProvider, instrumentations: [new otel.DocumentLoadInstrumentation()] });

    window.addEventListener('error', (event) => recordError(event.error ?? new Error(event.message)));
    window.addEventListener('unhandledrejection', (event) => recordError(event.reason));
    // The span and log processors flush themselves when the page is hidden; the metrics are flushed here, because
    // web vitals like LCP, INP and CLS are only final (and reported) at that moment.
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') meterProvider.forceFlush();
    });
    return true;
  } catch (error) {
    console.warn('Browser telemetry disabled:', error);
    otel = tracer = logger = actions = null;
    return false;
  }
}

function recordWebVitals(meter) {
  const histogram = (name, unit, description) => meter.createHistogram(`browser.web_vitals.${name}`, { unit, description });
  const lcp = histogram('lcp', 'ms', 'Largest Contentful Paint');
  const inp = histogram('inp', 'ms', 'Interaction to Next Paint');
  const fcp = histogram('fcp', 'ms', 'First Contentful Paint');
  const ttfb = histogram('ttfb', 'ms', 'Time to First Byte');
  const cls = histogram('cls', '1', 'Cumulative Layout Shift');
  const record = (instrument) => (metric) => instrument.record(metric.value, { rating: metric.rating });
  otel.onLCP(record(lcp));
  otel.onINP(record(inp));
  otel.onFCP(record(fcp));
  otel.onTTFB(record(ttfb));
  otel.onCLS(record(cls));
}

/**
 * Runs a user action as the root span of a new trace. `fn` receives the span; pass it to `startRequest` so that
 * the action's API calls become its children.
 */
export async function action(name, attributes, fn) {
  if (!tracer) return fn(null);
  const span = tracer.startSpan(name, { kind: otel.SpanKind.INTERNAL, attributes }, otel.ROOT_CONTEXT);
  try {
    const result = await fn(span);
    actions.add(1, { action: name, outcome: 'ok' });
    return result;
  } catch (error) {
    span.recordException(error);
    span.setStatus({ code: otel.SpanStatusCode.ERROR, message: String(error?.message ?? error) });
    recordError(error, span);
    actions.add(1, { action: name, outcome: 'error' });
    throw error;
  } finally {
    span.end();
  }
}

/**
 * Starts the client span for one API call and returns the headers to send (W3C traceparent) and a function to end
 * the span with the HTTP status. Unsampled requests (polling) get a plain, unsampled traceparent and no span.
 */
export function startRequest(parent, method, path, { sampled = true } = {}) {
  if (!sampled || !tracer) return { headers: { traceparent: plainTraceparent(sampled) }, end() {} };
  const parentContext = parent ? otel.trace.setSpan(otel.ROOT_CONTEXT, parent) : otel.ROOT_CONTEXT;
  const span = tracer.startSpan(`HTTP ${method}`, {
    kind: otel.SpanKind.CLIENT,
    attributes: { 'http.request.method': method, 'url.path': path },
  }, parentContext);
  const headers = {};
  otel.propagation.inject(otel.trace.setSpan(parentContext, span), headers);
  return {
    headers,
    end(status) {
      span.setAttribute('http.response.status_code', status);
      if (!status || status >= 500) span.setStatus({ code: otel.SpanStatusCode.ERROR });
      span.end();
    },
  };
}

/** An error as a log record, attached to the span's trace when there is one. */
export function recordError(error, span = null) {
  if (!logger) return;
  const err = error instanceof Error ? error : new Error(String(error));
  logger.emit({
    severityNumber: otel.SeverityNumber.ERROR,
    severityText: 'ERROR',
    body: err.message,
    attributes: { 'exception.type': err.name, 'exception.message': err.message, 'exception.stacktrace': err.stack ?? '' },
    context: span ? otel.trace.setSpan(otel.ROOT_CONTEXT, span) : undefined,
  });
}
