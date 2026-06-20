(function (React) {
  "use strict";

  var _a;

  function authHeaders() {
    var token = window.__elementsApiClient && window.__elementsApiClient.getSessionToken();
    return token ? { 'Elements-SessionSecret': token } : {};
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

  // ── Execution result (post-launch) ────────────────────────────────────────────

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

  // ── Generic detail grid ───────────────────────────────────────────────────────

  function DetailGrid(props) {
    var obj = props.obj;
    if (!obj || typeof obj !== 'object') return null;
    var keys = Object.keys(obj).filter(function (k) { return obj[k] != null; });
    if (keys.length === 0) return null;
    return React.createElement('div', {
      className: 'grid gap-x-6 gap-y-1 text-xs font-mono mt-2',
      style: { gridTemplateColumns: 'auto 1fr' }
    },
      keys.map(function (k) {
        var val = obj[k];
        var display = typeof val === 'object' ? JSON.stringify(val) : String(val);
        return [
          React.createElement('span', { key: k + '_k', className: 'text-muted-foreground whitespace-nowrap' }, k),
          React.createElement('span', { key: k + '_v', className: 'break-all' }, display)
        ];
      }).flat()
    );
  }

  // ── List editor (args / command) ──────────────────────────────────────────────

  function ListEditor(props) {
    var input = 'flex-1 rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary';
    return React.createElement('div', { className: 'space-y-1' },
      props.items.map(function (val, i) {
        return React.createElement('div', { key: i, className: 'flex items-center gap-1' },
          React.createElement('input', {
            className: input,
            value: val,
            onChange: function (e) { props.onChange(i, e.target.value); }
          }),
          React.createElement('button', {
            type: 'button',
            className: 'text-muted-foreground hover:text-destructive px-1',
            onClick: function () { props.onRemove(i); }
          }, '✕')
        );
      }),
      React.createElement('button', {
        type: 'button',
        className: 'text-xs text-primary hover:underline',
        onClick: props.onAdd
      }, '+ Add')
    );
  }

  // ── Key-value editor (environment) ───────────────────────────────────────────

  function KVEditor(props) {
    var input = 'flex-1 min-w-0 rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary';
    return React.createElement('div', { className: 'space-y-1' },
      props.items.map(function (pair, i) {
        return React.createElement('div', { key: i, className: 'flex items-center gap-1' },
          React.createElement('input', {
            className: input,
            value: pair.key,
            onChange: function (e) { props.onChangeKey(i, e.target.value); }
          }),
          React.createElement('span', { className: 'text-muted-foreground text-sm px-1' }, '='),
          React.createElement('input', {
            className: input,
            value: pair.value,
            onChange: function (e) { props.onChangeValue(i, e.target.value); }
          }),
          React.createElement('button', {
            type: 'button',
            className: 'text-muted-foreground hover:text-destructive px-1',
            onClick: function () { props.onRemove(i); }
          }, '✕')
        );
      }),
      React.createElement('button', {
        type: 'button',
        className: 'text-xs text-primary hover:underline',
        onClick: props.onAdd
      }, '+ Add')
    );
  }

  // ── Run form ──────────────────────────────────────────────────────────────────

  function RunForm(props) {
    var argsState       = React.useState([]);
    var args            = argsState[0],    setArgs    = argsState[1];
    var commandState    = React.useState([]);
    var command         = commandState[0], setCommand = commandState[1];
    var envState        = React.useState([]);
    var env             = envState[0],     setEnv     = envState[1];
    var placementState  = React.useState({ type: '', region: '', ip: '', lat: '', lon: '' });
    var placement       = placementState[0], setPlacement = placementState[1];
    var resultState     = React.useState(null);
    var resultData      = resultState[0],  setResult    = resultState[1];
    var submittingState = React.useState(false);
    var isSubmitting    = submittingState[0], setSubmitting = submittingState[1];
    var errorState      = React.useState(null);
    var submitError     = errorState[0],   setError     = errorState[1];

    function updateList(setter, i, val) {
      setter(function (list) { var n = list.slice(); n[i] = val; return n; });
    }
    function addToList(setter, empty) {
      setter(function (list) { return list.concat([empty]); });
    }
    function removeFromList(setter, i) {
      setter(function (list) { return list.filter(function (_, j) { return j !== i; }); });
    }
    function setP(key) {
      return function (e) {
        var val = e.target.value;
        setPlacement(function (p) { return Object.assign({}, p, { [key]: val }); });
      };
    }

    function handleSubmit(e) {
      e.preventDefault();
      setSubmitting(true); setError(null); setResult(null);

      var envMap = {};
      env.forEach(function (pair) { if (pair.key.trim()) envMap[pair.key.trim()] = pair.value; });

      var placementList = [];
      if (placement.type === 'REGION' && placement.region.trim())
        placementList = [{ type: 'REGION', region: placement.region.trim() }];
      else if (placement.type === 'IP_ADDRESS' && placement.ip.trim())
        placementList = [{ type: 'IP_ADDRESS', ip: placement.ip.trim() }];
      else if (placement.type === 'LAT_LON' && placement.lat && placement.lon)
        placementList = [{ type: 'LAT_LON', latitude: parseFloat(placement.lat), longitude: parseFloat(placement.lon) }];

      var body = {
        element: props.element,
        profileId: props.profileId,
        args:        args.map(function (s) { return s.trim(); }).filter(Boolean),
        command:     command.map(function (s) { return s.trim(); }).filter(Boolean),
        environment: envMap,
        placement:   placementList
      };

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

    var lbl   = 'block text-xs font-medium text-muted-foreground mb-1';
    var input = 'w-full rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary';

    return React.createElement('div', { className: 'mt-3 rounded-lg border bg-background p-4 space-y-3' },
      React.createElement('form', { onSubmit: handleSubmit, className: 'space-y-3' },

        React.createElement('div', { className: 'grid grid-cols-2 gap-3' },
          React.createElement('div', null,
            React.createElement('label', { className: lbl }, 'Command override'),
            React.createElement(ListEditor, {
              items: command,
              onChange: function (i, v) { updateList(setCommand, i, v); },
              onAdd:    function ()    { addToList(setCommand, ''); },
              onRemove: function (i)   { removeFromList(setCommand, i); }
            })
          ),
          React.createElement('div', null,
            React.createElement('label', { className: lbl }, 'Args'),
            React.createElement(ListEditor, {
              items: args,
              onChange: function (i, v) { updateList(setArgs, i, v); },
              onAdd:    function ()    { addToList(setArgs, ''); },
              onRemove: function (i)   { removeFromList(setArgs, i); }
            })
          )
        ),

        React.createElement('div', null,
          React.createElement('label', { className: lbl }, 'Environment variables'),
          React.createElement(KVEditor, {
            items: env,
            onChangeKey:   function (i, v) { updateList(setEnv, i, Object.assign({}, env[i], { key: v })); },
            onChangeValue: function (i, v) { updateList(setEnv, i, Object.assign({}, env[i], { value: v })); },
            onAdd:         function ()    { addToList(setEnv, { key: '', value: '' }); },
            onRemove:      function (i)   { removeFromList(setEnv, i); }
          })
        ),

        React.createElement('div', null,
          React.createElement('label', { className: lbl }, 'Placement'),
          React.createElement('select', { className: input, value: placement.type, onChange: setP('type') },
            React.createElement('option', { value: '' }, '— None —'),
            React.createElement('option', { value: 'REGION' }, 'Region'),
            React.createElement('option', { value: 'IP_ADDRESS' }, 'IP Address'),
            React.createElement('option', { value: 'LAT_LON' }, 'Lat / Lon')
          ),
          placement.type === 'REGION' &&
            React.createElement('input', { className: input + ' mt-1', value: placement.region, onChange: setP('region') }),
          placement.type === 'IP_ADDRESS' &&
            React.createElement('input', { className: input + ' mt-1', value: placement.ip, onChange: setP('ip') }),
          placement.type === 'LAT_LON' &&
            React.createElement('div', { className: 'flex gap-2 mt-1' },
              React.createElement('input', { className: input, value: placement.lat, onChange: setP('lat') }),
              React.createElement('input', { className: input, value: placement.lon, onChange: setP('lon') })
            )
        ),

        submitError && React.createElement('p', { className: 'text-xs text-destructive' }, submitError),
        resultData  && React.createElement(ExecutionResult, { execution: resultData }),

        React.createElement('div', { className: 'flex justify-end gap-2' },
          React.createElement('button', {
            type: 'button',
            className: 'px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors',
            onClick: props.onClose
          }, 'Cancel'),
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
      React.createElement('div', { className: 'flex items-center gap-3 px-4 py-3' },
        React.createElement('button', {
          className: 'flex-1 flex items-center gap-2 text-left',
          onClick: function () { setExpanded(function (v) { return !v; }); }
        },
          React.createElement('span', { className: 'text-xs opacity-50' }, isExpanded ? '▼' : '▶'),
          React.createElement('span', { className: 'font-mono text-sm font-medium' }, profile.id)
        ),
        React.createElement('button', {
          className: 'flex items-center gap-1.5 px-3 py-1 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors',
          onClick: function () { props.isRunning ? props.onRunClose() : props.onRun(profile.id); }
        },
          props.isRunning ? '✕ Cancel' : '▶ Run'
        )
      ),
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

  // ── Running job row ───────────────────────────────────────────────────────────

  function RunningJobRow(props) {
    var ex = props.execution;
    var element = props.element;
    var expandedState = React.useState(false);
    var isExpanded = expandedState[0], setExpanded = expandedState[1];
    var stoppingState = React.useState(false);
    var isStopping = stoppingState[0], setStopping = stoppingState[1];
    var stopErrorState = React.useState(null);
    var stopError = stopErrorState[0], setStopError = stopErrorState[1];

    var statusColor = ex.status === 'RUNNING'   ? 'text-green-700 bg-green-50'
                    : ex.status === 'PENDING'   ? 'text-yellow-700 bg-yellow-50'
                    : ex.status === 'FAILED'    ? 'text-destructive bg-destructive/10'
                    : 'text-muted-foreground bg-muted';

    function handleStop() {
      setStopping(true);
      setStopError(null);
      fetch('/conductor/admin/jobs/stop', {
        method: 'POST', credentials: 'include',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ element: element, id: ex.id })
      }).then(function (res) {
        if (!res.ok) {
          return res.json().then(function (body) {
            setStopError(body.error || ('Stop failed: ' + res.status));
          }).catch(function () {
            setStopError('Stop failed: ' + res.status);
          });
        }
        props.onRefresh();
      }).catch(function (e) {
        setStopError(e.message || 'Network error');
      }).finally(function () {
        setStopping(false);
      });
    }

    return React.createElement('div', { className: 'rounded-lg border bg-card' },
      React.createElement('div', { className: 'flex items-center gap-3 px-4 py-2.5 flex-wrap' },
        React.createElement('button', {
          className: 'text-xs opacity-50 shrink-0',
          onClick: function () { setExpanded(function (v) { return !v; }); }
        }, isExpanded ? '▼' : '▶'),
        React.createElement('span', { className: 'font-mono text-xs break-all flex-1 min-w-0' }, ex.id),
        React.createElement('span', {
          className: 'text-xs font-medium px-2 py-0.5 rounded-full shrink-0 ' + statusColor
        }, ex.status),
        ex.endpoints && ex.endpoints.length > 0 &&
          React.createElement('span', { className: 'font-mono text-xs text-muted-foreground shrink-0' },
            ex.endpoints.map(function (ep) { return ep.host + ':' + ep.port + '/' + ep.protocol; }).join(', ')
          ),
        React.createElement('button', {
          disabled: isStopping,
          onClick: handleStop,
          className: 'shrink-0 px-2.5 py-1 rounded border text-xs text-destructive border-destructive/40 hover:bg-destructive/10 disabled:opacity-50 transition-colors'
        }, isStopping ? 'Stopping…' : 'Stop')
      ),
      stopError &&
        React.createElement('div', { className: 'border-t px-4 py-2 text-xs text-destructive font-mono' }, stopError),
      isExpanded && ex.details &&
        React.createElement('div', { className: 'border-t px-4 py-3' },
          React.createElement(DetailGrid, { obj: ex.details })
        )
    );
  }

  // ── Running jobs section ──────────────────────────────────────────────────────

  function RunningJobsSection(props) {
    var stateHolder = React.useState({ loading: true, providers: [], error: null });
    var data = stateHolder[0], setData = stateHolder[1];

    function load() {
      fetch('/conductor/admin/jobs', { credentials: 'include', headers: authHeaders() })
        .then(function (res) { return res.json(); })
        .then(function (body) { setData({ loading: false, providers: body.providers || [], error: null }); })
        .catch(function (e) { setData({ loading: false, providers: [], error: e.message }); });
    }

    React.useEffect(function () {
      load();
      var interval = setInterval(load, 10000);
      return function () { clearInterval(interval); };
    }, []);

    var allExecutions = data.providers.reduce(function (acc, p) {
      if (!p.executions) return acc;
      return acc.concat(p.executions.map(function (ex) { return { element: p.element, execution: ex }; }));
    }, []);

    return React.createElement('div', { className: 'space-y-3' },
      React.createElement('div', { className: 'flex items-center justify-between' },
        React.createElement('h2', { className: 'text-lg font-semibold' }, 'Running Jobs'),
        React.createElement('button', {
          onClick: load,
          disabled: data.loading,
          className: 'flex items-center gap-1.5 px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors disabled:opacity-50'
        },
          data.loading
            ? React.createElement('span', { className: 'w-3 h-3 rounded-full bg-gray-400 animate-pulse inline-block' })
            : '↺',
          data.loading ? ' Refreshing…' : ' Refresh'
        )
      ),
      data.error && React.createElement('p', { className: 'text-xs text-destructive' }, data.error),
      !data.loading && allExecutions.length === 0 &&
        React.createElement('p', { className: 'text-sm text-muted-foreground' }, 'No active jobs found.'),
      allExecutions.length > 0 &&
        React.createElement('div', { className: 'space-y-2' },
          allExecutions.map(function (item, i) {
            return React.createElement('div', { key: item.element + ':' + item.execution.id },
              React.createElement('div', { className: 'text-xs text-muted-foreground mb-1 font-mono' }, item.element),
              React.createElement(RunningJobRow, {
                execution: item.execution,
                element: item.element,
                onRefresh: load
              })
            );
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

    return React.createElement('div', { className: 'p-6 max-w-3xl space-y-8' },

      React.createElement('div', { className: 'space-y-6' },
        React.createElement('div', { className: 'flex items-center justify-between' },
          React.createElement('h1', { className: 'text-2xl font-bold' }, 'Namazu Conductor Jobs'),
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
      ),

      React.createElement('hr', { className: 'border-border' }),

      React.createElement(RunningJobsSection, null)
    );
  }

  (_a = window.__elementsPlugins) == null ? void 0 : _a.register('conductor-admin', ConductorAdmin);
})(window.React);