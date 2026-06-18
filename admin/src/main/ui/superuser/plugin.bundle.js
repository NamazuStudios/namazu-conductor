    (function (React) {
  "use strict";

  var _a;

  function authHeaders() {
    var token = window.__elementsApiClient && window.__elementsApiClient.getSessionToken();
    return token ? { 'Elements-SessionSecret': token } : {};
  }

  function parseEnvLines(text) {
    var result = {};
    (text || '').split('\n').forEach(function (line) {
      var eq = line.indexOf('=');
      if (eq > 0) result[line.slice(0, eq).trim()] = line.slice(eq + 1);
    });
    return result;
  }

  function parseLines(text) {
    return (text || '').split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
  }

  // ── Status indicator ──────────────────────────────────────────────────────────

  function StatusIndicator(props) {
    var dot, label, labelClass;
    if (props.loading) {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-gray-400 animate-pulse' });
      label = 'Checking…'; labelClass = 'text-muted-foreground';
    } else if (props.status === 'ok') {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-green-500' });
      label = 'Ready'; labelClass = 'text-green-700 font-medium';
    } else if (props.status === 'partial') {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-yellow-500' });
      label = 'Partial'; labelClass = 'text-yellow-700 font-medium';
    } else {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-red-500' });
      label = props.error || 'Unavailable'; labelClass = 'text-destructive font-medium';
    }
    return React.createElement('span', { className: 'inline-flex items-center gap-2 text-sm' },
      dot, React.createElement('span', { className: labelClass }, label)
    );
  }

  // ── Execution result ──────────────────────────────────────────────────────────

  function ExecutionResult(props) {
    var ex = props.execution;
    var statusColor = ex.status === 'RUNNING' ? 'text-green-700'
      : ex.status === 'PENDING'  ? 'text-yellow-700'
      : ex.status === 'FAILED'   ? 'text-destructive'
      : 'text-muted-foreground';
    return React.createElement('div', { className: 'rounded-lg border bg-muted/30 p-3 space-y-2 text-sm mt-3' },
      React.createElement('div', { className: 'flex items-center gap-3 flex-wrap' },
        React.createElement('span', { className: 'font-semibold' }, 'Job launched'),
        React.createElement('span', { className: 'font-mono text-xs bg-muted px-2 py-0.5 rounded' }, ex.id),
        React.createElement('span', { className: 'font-medium ' + statusColor }, ex.status)
      ),
      ex.endpoints && ex.endpoints.length > 0 &&
        React.createElement('div', { className: 'space-y-1' },
          ex.endpoints.map(function (ep, i) {
            return React.createElement('div', { key: i, className: 'font-mono text-xs text-muted-foreground' },
              ep.host + ':' + ep.port + '/' + ep.protocol);
          })
        )
    );
  }

  // ── Run form ──────────────────────────────────────────────────────────────────

  function RunForm(props) {
    var formState = React.useState({ args: '', command: '', env: '', placementType: '', region: '', ip: '', lat: '', lon: '' });
    var fields = formState[0], setFields = formState[1];
    var resultState = React.useState(null);
    var resultData = resultState[0], setResult = resultState[1];
    var submittingState = React.useState(false);
    var isSubmitting = submittingState[0], setSubmitting = submittingState[1];
    var errorState = React.useState(null);
    var submitError = errorState[0], setError = errorState[1];

    function set(key) {
      return function (e) {
        var val = e.target.value;
        setFields(function (f) { return Object.assign({}, f, { [key]: val }); });
      };
    }

    function handleSubmit(e) {
      e.preventDefault();
      setSubmitting(true); setError(null); setResult(null);
      var body = {
        element: props.element, profileId: props.profileId,
        args: parseLines(fields.args), command: parseLines(fields.command),
        environment: parseEnvLines(fields.env), placement: []
      };
      if (fields.placementType === 'REGION' && fields.region.trim())
        body.placement = [{ type: 'REGION', region: fields.region.trim() }];
      else if (fields.placementType === 'IP_ADDRESS' && fields.ip.trim())
        body.placement = [{ type: 'IP_ADDRESS', ip: fields.ip.trim() }];
      else if (fields.placementType === 'LAT_LON' && fields.lat && fields.lon)
        body.placement = [{ type: 'LAT_LON', latitude: parseFloat(fields.lat), longitude: parseFloat(fields.lon) }];

      fetch('/conductor/admin/jobs', {
        method: 'POST', credentials: 'include',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify(body)
      }).then(function (res) {
        return res.json().then(function (data) { return { ok: res.ok, data: data }; });
      }).then(function (r) {
        r.ok ? setResult(r.data) : setError(r.data.error || 'Request failed');
      }).catch(function (e) {
        setError(e.message || 'Network error');
      }).finally(function () { setSubmitting(false); });
    }

    var input = 'w-full rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary';
    var ta    = input + ' resize-none';
    var lbl   = 'block text-xs font-medium text-muted-foreground mb-1';

    return React.createElement('div', { className: 'mt-3 rounded-lg border bg-background p-4 space-y-3' },
      React.createElement('form', { onSubmit: handleSubmit, className: 'space-y-3' },
        React.createElement('div', { className: 'grid grid-cols-2 gap-3' },
          React.createElement('div', null,
            React.createElement('label', { className: lbl }, 'Command override (one token per line)'),
            React.createElement('textarea', { className: ta, rows: 2, value: fields.command, onChange: set('command'), placeholder: '/bin/sh\n-c' })
          ),
          React.createElement('div', null,
            React.createElement('label', { className: lbl }, 'Args (one per line)'),
            React.createElement('textarea', { className: ta, rows: 2, value: fields.args, onChange: set('args'), placeholder: '--port\n7777' })
          )
        ),
        React.createElement('div', null,
          React.createElement('label', { className: lbl }, 'Environment variables (KEY=value, one per line)'),
          React.createElement('textarea', { className: ta, rows: 3, value: fields.env, onChange: set('env'), placeholder: 'SERVER_PORT=7777\nGAME_MODE=deathmatch' })
        ),
        React.createElement('div', null,
          React.createElement('label', { className: lbl }, 'Placement (optional)'),
          React.createElement('select', { className: input, value: fields.placementType, onChange: set('placementType') },
            React.createElement('option', { value: '' }, '— None —'),
            React.createElement('option', { value: 'REGION' }, 'Region'),
            React.createElement('option', { value: 'IP_ADDRESS' }, 'IP Address'),
            React.createElement('option', { value: 'LAT_LON' }, 'Lat / Lon')
          ),
          fields.placementType === 'REGION' &&
            React.createElement('input', { className: input + ' mt-1', placeholder: 'Region ID', value: fields.region, onChange: set('region') }),
          fields.placementType === 'IP_ADDRESS' &&
            React.createElement('input', { className: input + ' mt-1', placeholder: 'IP address', value: fields.ip, onChange: set('ip') }),
          fields.placementType === 'LAT_LON' &&
            React.createElement('div', { className: 'flex gap-2 mt-1' },
              React.createElement('input', { className: input, placeholder: 'Latitude',  value: fields.lat, onChange: set('lat') }),
              React.createElement('input', { className: input, placeholder: 'Longitude', value: fields.lon, onChange: set('lon') })
            )
        ),
        submitError && React.createElement('p', { className: 'text-xs text-destructive' }, submitError),
        resultData  && React.createElement(ExecutionResult, { execution: resultData }),
        React.createElement('div', { className: 'flex justify-end gap-2' },
          React.createElement('button', { type: 'button', className: 'px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors', onClick: props.onClose }, 'Cancel'),
          React.createElement('button', {
            type: 'submit', disabled: isSubmitting,
            className: 'px-4 py-1.5 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50'
          }, isSubmitting ? 'Launching…' : '▶ Launch Job')
        )
      )
    );
  }

  // ── Profile card ──────────────────────────────────────────────────────────────

  function ProfileCard(props) {
    var profile = props.profile;
    var expandedState = React.useState(false);
    var isExpanded = expandedState[0], setExpanded = expandedState[1];
    var keys = Object.keys(profile).filter(function (k) { return k !== 'id'; });

    return React.createElement('div', { className: 'rounded-lg border bg-card' },
      // Header row — always visible
      React.createElement('div', { className: 'flex items-center gap-3 px-4 py-3' },
        React.createElement('button', {
          className: 'flex-1 flex items-center gap-2 text-left',
          onClick: function () { setExpanded(function (v) { return !v; }); }
        },
          React.createElement('span', { className: 'text-xs opacity-50' }, isExpanded ? '▲' : '▼'),
          React.createElement('span', { className: 'font-mono text-sm font-medium' }, profile.id)
        ),
        React.createElement('button', {
          className: 'flex items-center gap-1.5 px-3 py-1 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors',
          onClick: function () {
            props.isRunning ? props.onRunClose() : props.onRun(profile.id);
          }
        },
          props.isRunning
            ? React.createElement(React.Fragment, null, '✕ Cancel')
            : React.createElement(React.Fragment, null,
                React.createElement('span', null, '▶'),
                React.createElement('span', null, 'Run')
              )
        )
      ),
      // Expanded field list
      isExpanded && keys.length > 0 &&
        React.createElement('div', { className: 'border-t px-4 py-3 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5' },
          keys.map(function (k) {
            var val = profile[k];
            return [
              React.createElement('span', { key: k + '_k', className: 'text-xs font-medium text-muted-foreground whitespace-nowrap' }, k),
              React.createElement('span', { key: k + '_v', className: 'text-xs font-mono break-all' },
                val == null ? React.createElement('span', { className: 'text-muted-foreground' }, '—') : String(val)
              )
            ];
          }).flat()
        ),
      // Run form
      props.isRunning &&
        React.createElement('div', { className: 'border-t px-4 pb-4' },
          React.createElement(RunForm, { element: props.element, profileId: profile.id, onClose: props.onRunClose })
        )
    );
  }

  // ── Provider section ──────────────────────────────────────────────────────────

  function ProviderSection(props) {
    var p = props.provider;
    var expandedState = React.useState(false);
    var isExpanded = expandedState[0], setExpanded = expandedState[1];
    var activeRunState = React.useState(null);
    var activeProfileId = activeRunState[0], setActiveProfileId = activeRunState[1];

    return React.createElement('div', { className: 'space-y-2' },
      // Provider header
      React.createElement('div', { className: 'flex items-center gap-3 flex-wrap' },
        React.createElement('span', { className: 'font-mono text-sm font-medium' }, p.element),
        p.providerType && React.createElement('span', {
          className: 'text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary'
        }, p.providerType),
        !p.error && React.createElement('span', {
          className: 'text-xs px-2 py-0.5 rounded-full ' +
            (p.profiles && p.profiles.length > 0 ? 'bg-muted text-muted-foreground' : 'bg-yellow-100 text-yellow-700')
        }, p.profiles ? p.profiles.length + ' Job Profile' + (p.profiles.length === 1 ? '' : 's') : '0 Job Profiles'),
        p.error && React.createElement('button', {
          className: 'text-xs px-2 py-0.5 rounded-full bg-destructive/10 text-destructive hover:bg-destructive/20 transition-colors',
          onClick: function () { setExpanded(function (v) { return !v; }); }
        },
          p.error.length > 60 ? p.error.slice(0, 60) + '…' : p.error,
          React.createElement('span', { className: 'ml-1 opacity-60' }, isExpanded ? '▲' : '▼')
        )
      ),
      isExpanded && p.error && React.createElement('pre', {
        className: 'rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive whitespace-pre-wrap break-all'
      }, p.error),
      // Profile cards
      p.profiles && p.profiles.length > 0 &&
        React.createElement('div', { className: 'space-y-2' },
          p.profiles.map(function (profile) {
            return React.createElement(ProfileCard, {
              key: profile.id,
              profile: profile,
              element: p.element,
              isRunning: activeProfileId === profile.id,
              onRun: setActiveProfileId,
              onRunClose: function () { setActiveProfileId(null); }
            });
          })
        )
    );
  }

  // ── Root ──────────────────────────────────────────────────────────────────────

  function ConductorAdmin() {
    var state = React.useState({ loading: true, status: null, error: null, providers: [] });
    var data = state[0], setData = state[1];

    React.useEffect(function () {
      (async function () {
        try {
          var res = await fetch('/conductor/admin/profiles', { credentials: 'include', headers: authHeaders() });
          if (res.status === 403) throw new Error('Access denied (SUPERUSER required)');
          if (res.status === 503) throw new Error('No providers deployed');
          if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
          var body = await res.json();
          setData({ loading: false, status: body.status, error: null, providers: body.providers || [] });
        } catch (e) {
          setData({ loading: false, status: 'error', error: e instanceof Error ? e.message : String(e), providers: [] });
        }
      })();
    }, []);

    return React.createElement('div', { className: 'p-6 max-w-3xl space-y-6' },
      React.createElement('div', { className: 'flex items-center justify-between' },
        React.createElement('h1', { className: 'text-2xl font-bold' }, 'Conductor — Job Profiles'),
        React.createElement(StatusIndicator, { loading: data.loading, status: data.status, error: data.error })
      ),
      !data.loading && data.status === 'error' && data.providers.length === 0 &&
        React.createElement('div', {
          className: 'rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive'
        }, data.error || 'Failed to load profiles.'),
      !data.loading && data.providers.length > 0 &&
        React.createElement('div', { className: 'space-y-6' },
          data.providers.map(function (p) {
            return React.createElement(ProviderSection, { key: p.element, provider: p });
          })
        )
    );
  }

  (_a = window.__elementsPlugins) == null ? void 0 : _a.register('conductor-admin', ConductorAdmin);
})(window.React);
