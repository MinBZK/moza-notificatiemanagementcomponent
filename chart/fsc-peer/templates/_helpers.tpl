{{- define "fsc-peer.prefix" -}}
{{- required "prefix is verplicht (gelijk aan de SAN's in de certificaten)" .Values.prefix -}}
{{- end }}

{{- define "fsc-peer.labels" -}}
app.kubernetes.io/part-of: fsc-peer
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- /* Adres van een component binnen de namespace, bijvoorbeeld https://nmc-fscmgr:9443. */ -}}
{{- define "fsc-peer.svc" -}}
https://{{ include "fsc-peer.prefix" .root }}-{{ .short }}:{{ .port }}
{{- end }}

{{- /* Database-env: het wachtwoord uit het Secret dat CloudNativePG aanmaakt, met een eigen search_path per
component (manager en txlog), zodat hun migratietellers niet botsen. De controller maakt zijn eigen schema. */ -}}
{{- define "fsc-peer.dsn" -}}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "fsc-peer.prefix" .root }}-fscpg-app
      key: password
- name: STORAGE_POSTGRES_DSN
  value: "postgres://fsc:$(DB_PASSWORD)@{{ include "fsc-peer.prefix" .root }}-fscpg-rw:5432/fsc?sslmode=require{{ with .schema }}&search_path={{ . }}{{ end }}"
{{- end }}

{{- define "fsc-peer.tls-internal" -}}
- name: TLS_ROOT_CERT
  value: /etc/fsc/internal/root.pem
- name: TLS_CERT
  value: /etc/fsc/internal/cert.pem
- name: TLS_KEY
  value: /etc/fsc/internal/key.pem
{{- end }}

{{- /* CRL-controle uit, net als bij de andere peers: de CRL van het testnet heeft geen distributiepunt. */ -}}
{{- define "fsc-peer.tls-group" -}}
- name: DISABLE_CRL_CHECKS
  value: "true"
- name: TLS_GROUP_ROOT_CERT
  value: /etc/fsc/ca/root.pem
- name: TLS_GROUP_CERT
  value: /etc/fsc/group/cert.pem
- name: TLS_GROUP_KEY
  value: /etc/fsc/group/key.pem
{{- end }}

{{- /*
Deployment en Service van één component. Argumenten (dict):
  root      de chart-context
  endpoint  naam in het PKI-Secret: manager, controller, txlog, inway, outway
  short     componentnaam: fscmgr, fscctl, fsctxlog, fscinway, fscoutway
  image     image met digest
  binary    als gezet: eerst "<binary> migrate up" in een init-container (manager, controller, txlog)
  group     true als het component een group-certificaat heeft
  ports     lijst van {name, port}; de eerste is ook de readiness-probe
  env       env-lijst als YAML
*/ -}}
{{- define "fsc-peer.component" -}}
{{- $root := .root }}
{{- $name := printf "%s-%s" (include "fsc-peer.prefix" $root) .short }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "fsc-peer.labels" $root | nindent 4 }}
    app.kubernetes.io/name: {{ $name }}
spec:
  # Eén replica: twee gelijktijdige migraties op dezelfde database beschadigen de migratiestand.
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app.kubernetes.io/instance: {{ $root.Release.Name }}
      app.kubernetes.io/name: {{ $name }}
  template:
    metadata:
      labels:
        {{- include "fsc-peer.labels" $root | nindent 8 }}
        app.kubernetes.io/name: {{ $name }}
      annotations:
        # Nieuwe certificaten in het Secret: pods herstarten bij de volgende helm upgrade.
        checksum/pki: {{ (lookup "v1" "Secret" $root.Release.Namespace $root.Values.pkiSecret).data | toJson | sha256sum }}
    spec:
      automountServiceAccountToken: false
      securityContext:
        {{- toYaml $root.Values.podSecurityContext | nindent 8 }}
      {{- if .binary }}
      initContainers:
        - name: migrate
          image: {{ .image }}
          command: ["{{ .binary }}", "migrate", "up", "--postgres-dsn", "$(STORAGE_POSTGRES_DSN)"]
          env:
            {{- .env | nindent 12 }}
          securityContext:
            {{- toYaml $root.Values.containerSecurityContext | nindent 12 }}
          resources:
            {{- toYaml $root.Values.resources | nindent 12 }}
      {{- end }}
      containers:
        - name: {{ .short }}
          image: {{ .image }}
          env:
            {{- .env | nindent 12 }}
          ports:
            {{- range .ports }}
            - name: {{ .name }}
              containerPort: {{ .port }}
            {{- end }}
          readinessProbe:
            tcpSocket:
              port: {{ (first .ports).port }}
            periodSeconds: 10
          securityContext:
            {{- toYaml $root.Values.containerSecurityContext | nindent 12 }}
          resources:
            {{- toYaml $root.Values.resources | nindent 12 }}
          volumeMounts:
            - name: pki
              mountPath: /etc/fsc
              readOnly: true
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir:
            sizeLimit: 64Mi
        # Alleen de bestanden van dit component. 0440 met fsGroup: leesbaar voor de container, niet daarbuiten.
        - name: pki
          secret:
            secretName: {{ $root.Values.pkiSecret }}
            defaultMode: 0440
            items:
              - key: internal-root.pem
                path: internal/root.pem
              - key: internal-{{ .endpoint }}-cert.pem
                path: internal/cert.pem
              - key: internal-{{ .endpoint }}-key.pem
                path: internal/key.pem
              {{- if .group }}
              - key: ca-root.pem
                path: ca/root.pem
              - key: group-{{ .endpoint }}-cert.pem
                path: group/cert.pem
              - key: group-{{ .endpoint }}-key.pem
                path: group/key.pem
              {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    {{- include "fsc-peer.labels" $root | nindent 4 }}
spec:
  selector:
    app.kubernetes.io/instance: {{ $root.Release.Name }}
    app.kubernetes.io/name: {{ $name }}
  ports:
    {{- range .ports }}
    - name: {{ .name }}
      port: {{ .port }}
      targetPort: {{ .name }}
    {{- end }}
{{- end }}
