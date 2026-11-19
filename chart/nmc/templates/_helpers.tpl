{{- define "nmc.labels" -}}
app.kubernetes.io/name: nmc
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end }}

{{- define "nmc.selectorLabels" -}}
app.kubernetes.io/name: nmc
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- /* Een env-var uit het Secret van de NMC; de sleutel in het Secret is de naam van de env-var. */}}
{{- define "nmc.geheim" -}}
- name: {{ .naam }}
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: {{ .naam }}
{{- end }}
