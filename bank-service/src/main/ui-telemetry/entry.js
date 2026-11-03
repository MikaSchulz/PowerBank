// The OpenTelemetry browser pieces the UI needs, bundled by esbuild into one ES module:
// ../resources/static/vendor/otel-web.js. The UI's own telemetry code lives in static/telemetry.js.
export { trace, context, propagation, ROOT_CONTEXT, SpanKind, SpanStatusCode, SeverityNumber } from '@opentelemetry/api';
export { TracerProvider, BatchSpanProcessor, StackContextManager } from '@opentelemetry/sdk-trace';
export { W3CTraceContextPropagator } from '@opentelemetry/core';
export { resourceFromAttributes } from '@opentelemetry/resources';
export { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
export { LoggerProvider, BatchLogRecordProcessor } from '@opentelemetry/sdk-logs';
export { OTLPLogExporter } from '@opentelemetry/exporter-logs-otlp-http';
export { MeterProvider, PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
export { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
export { registerInstrumentations } from '@opentelemetry/instrumentation';
export { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
export { onCLS, onFCP, onINP, onLCP, onTTFB } from 'web-vitals';
