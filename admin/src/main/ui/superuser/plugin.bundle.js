(function (React) {
  "use strict";

  var _a;

  function authHeaders() {
    var token = window.__elementsApiClient && window.__elementsApiClient.getSessionToken();
    return token ? { 'Elements-SessionSecret': token } : {};
  }

  function StatusIndicator(props) {
    var dot, label, labelClass;
    if (props.loading) {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-gray-400 animate-pulse' });
      label = 'Checking…';
      labelClass = 'text-muted-foreground';
    } else if (props.status === 'ok') {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-green-500' });
      label = 'Ready';
      labelClass = 'text-green-700 font-medium';
    } else if (props.status === 'partial') {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-yellow-500' });
      label = 'Partial';
      labelClass = 'text-yellow-700 font-medium';
    } else {
      dot = React.createElement('span', { className: 'w-3 h-3 rounded-full bg-red-500' });
      label = props.error || 'Unavailable';
      labelClass = 'text-destructive font-medium';
    }
    return React.createElement('span', { className: 'inline-flex items-center gap-2 text-sm' },
      dot, React.createElement('span', { className: labelClass }, label)
    );
  }

  function ProfileTable(props) {
    var profiles = props.profiles;
    if (!profiles || profiles.length === 0) {
      return React.createElement('p', { className: 'text-sm text-muted-foreground italic' }, 'No profiles available.');
    }
    var keys = Object.keys(profiles[0]);
    return React.createElement('div', { className: 'overflow-x-auto rounded-lg border' },
      React.createElement('table', { className: 'min-w-full text-left text-sm' },
        React.createElement('thead', { className: 'border-b bg-muted/50' },
          React.createElement('tr', null,
            keys.map(function (k) {
              return React.createElement('th', {
                key: k,
                className: 'px-4 py-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground'
              }, k);
            })
          )
        ),
        React.createElement('tbody', null,
          profiles.map(function (profile, i) {
            return React.createElement('tr', {
              key: profile.id || i,
              className: 'border-t'
            },
              keys.map(function (k) {
                var val = profile[k];
                return React.createElement('td', { key: k, className: 'px-4 py-2 align-top' },
                  val == null
                    ? React.createElement('span', { className: 'text-muted-foreground' }, '—')
                    : React.createElement('span', { className: 'font-mono' }, String(val))
                );
              })
            );
          })
        )
      )
    );
  }

  function ProviderSection(props) {
    var p = props.provider;
    var expanded = React.useState(false);
    var isExpanded = expanded[0];
    var setExpanded = expanded[1];

    return React.createElement('div', { className: 'space-y-2' },
      React.createElement('div', { className: 'flex items-center gap-3 flex-wrap' },
        React.createElement('span', { className: 'font-mono text-sm font-medium' }, p.element),
        p.providerType && React.createElement('span', {
          className: 'text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary'
        }, p.providerType),
        !p.error && React.createElement('span', {
          className: 'text-xs px-2 py-0.5 rounded-full ' +
            (p.profiles && p.profiles.length > 0
              ? 'bg-muted text-muted-foreground'
              : 'bg-yellow-100 text-yellow-700')
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
      p.profiles && React.createElement(ProfileTable, { profiles: p.profiles })
    );
  }

  function ConductorAdmin() {
    var state = React.useState({ loading: true, status: null, error: null, providers: [] });
    var data = state[0];
    var setData = state[1];

    React.useEffect(function () {
      (async function () {
        try {
          var res = await fetch('/conductor/admin/profiles', {
            credentials: 'include',
            headers: authHeaders()
          });
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

    return React.createElement('div', { className: 'p-6 max-w-4xl space-y-6' },
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