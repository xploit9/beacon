"""Owner-scoped voice choice and private xAI admission; no provider keys leave the host."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import time

PROVIDERS = ('gemini', 'grok', 'openai')
VOICES = {'gemini': ('Aoede','Puck','Charon','Kore','Fenrir','Leda','Orus','Zephyr'),
          'grok': ('eve','ara','rex','sal','leo'),
          'openai': ('alloy','ash','ballad','coral','echo','sage','shimmer','verse','marin','cedar')}
PERSONALITIES = {'balanced': 'Use a natural, balanced conversational tone.',
                 'concise': 'Speak concisely and directly. Prefer short answers unless detail is requested.',
                 'warm': 'Speak warmly and patiently, with a friendly conversational tone.'}


def options_key(owner, profile, provider):
    return preference_key(owner, profile) + ':' + provider


def provider_selection(mobile, owner, profile, cfg, provider):
    value=configuration(cfg,provider)
    with mobile.connect() as conn:
        row=conn.execute('SELECT value FROM meta WHERE key=?',(options_key(owner,profile,provider),)).fetchone()
    if row:
        try: options=json.loads(row[0])
        except (TypeError,ValueError): options={}
        if isinstance(options,dict):
            try: value.update(validate_options(provider,options))
            except ValueError: pass
    value['personality_instruction']=PERSONALITIES[value['personality']]
    return value


def validate_options(provider, value):
    if provider not in PROVIDERS: raise ValueError('Unknown voice model')
    options={k:value[k] for k in ('voice','personality') if k in value}
    if 'voice' in options and (not isinstance(options['voice'],str) or options['voice'] not in VOICES[provider]): raise ValueError('Unsupported provider voice')
    if 'personality' in options and (not isinstance(options['personality'],str) or options['personality'] not in PERSONALITIES): raise ValueError('Unsupported personality')
    return options



def configuration(cfg, provider='gemini'):
    if provider not in PROVIDERS:
        raise ValueError('Unknown voice model')
    if provider == 'gemini':
        model, voice = cfg.get('model', 'gemini-3.8-live'), cfg.get('voice', 'Aoede')
    elif provider == 'grok':
        model, voice = 'grok-voice-latest', 'eve'
    else:
        model, voice = cfg.get('openai_model', 'gpt-realtime-2.1'), cfg.get('openai_voice', 'marin')
    return {'provider': provider, 'model': model, 'voice': voice,
            'personality': 'balanced', 'personality_instruction': PERSONALITIES['balanced'], 'transport': provider + '_live_direct', 'capabilities': {'vision': provider == 'gemini'}}


def preference_key(owner, profile):
    return 'voice-provider:' + hashlib.sha256(json.dumps([owner, profile]).encode()).hexdigest()


def selected(mobile, owner, profile, cfg):
    with mobile.connect() as conn:
        row = conn.execute('SELECT value FROM meta WHERE key=?', (preference_key(owner, profile),)).fetchone()
    return provider_selection(mobile,owner,profile,cfg,row[0] if row else 'gemini')


def bound(row, cfg):
    # Calls admitted before this feature were Gemini calls, even if the preference changed.
    try: value = json.loads(row.get('voice_config') or '{}')
    except (TypeError,ValueError): raise ValueError('Invalid voice admission') from None
    if not isinstance(value,dict): raise ValueError('Invalid voice admission')
    if not value:
        return configuration(cfg)
    provider=value.get('provider')
    if provider not in PROVIDERS or not isinstance(value.get('model'),str) or not value['model']:
        raise ValueError('Invalid voice admission')
    try: options=validate_options(provider,value)
    except ValueError: raise ValueError('Invalid voice admission') from None
    if 'voice' not in options: raise ValueError('Invalid voice admission')
    personality=options.get('personality','balanced')
    selection=configuration(cfg,provider)
    selection.update(model=value['model'],voice=options['voice'],personality=personality,
                     personality_instruction=PERSONALITIES[personality])
    return selection


def xai_secret(cfg):
    path = cfg.get('xai_credentials_file')
    if path:
        file = Path(path).resolve(strict=True)
        if not file.is_file() or file.stat().st_size > 16384:
            raise ValueError('Invalid private credential file')
        value = file.read_text(encoding='utf-8-sig').strip()
        if not re.fullmatch(r'xai-[A-Za-z0-9_-]{20,512}', value):
            raise ValueError('Invalid private credential')
        return value
    from agent.secret_scope import get_secret
    return get_secret('XAI_API_KEY')


def choices(cfg, google_secret, mobile=None, owner=None, profile=None):
    result = []
    for provider in PROVIDERS:
        configuration_error=None
        if provider=='gemini':
            try: configured=bool(google_secret(cfg))
            except FileNotFoundError:
                configured=False;configuration_error='Configured Google credentials file is missing'
            except (OSError,UnicodeError,RuntimeError):
                configured=False;configuration_error='Configured Google credentials file is unavailable'
            except ImportError:
                configured=False;configuration_error='Google credential store is unavailable'
            except ValueError:
                configured=False;configuration_error='Configured Google credentials are invalid'
        elif provider=='grok':
            try: configured=bool(xai_secret(cfg))
            except FileNotFoundError:
                configured=False;configuration_error='Configured xAI credentials file is missing'
            except (OSError,UnicodeError):
                configured=False;configuration_error='Configured xAI credentials file is unavailable'
            except ImportError:
                configured=False;configuration_error='xAI credential store is unavailable'
            except ValueError:
                configured=False;configuration_error='Configured xAI credentials are invalid'
        else: configured=False
        try:
            available = configured and (provider == 'grok' or provider == 'gemini' and importlib.util.find_spec('google.genai') is not None)
        except Exception:
            available = False
        value = provider_selection(mobile,owner,profile,cfg,provider) if mobile else configuration(cfg,provider)
        label = {'gemini': 'Gemini Live', 'grok': 'Grok Voice', 'openai': 'OpenAI Realtime'}[provider]
        choice={'id': provider, 'label': label,
                'model': value['model'], 'voice': value['voice'], 'voices': list(VOICES[provider]),
                'personality':value['personality'], 'personalities':[{'id':k,'label':k.title()} for k in PERSONALITIES], 'available': available,
                'configured': configured, 'vision': value['capabilities']['vision']}
        if configuration_error: choice['configuration_error']=configuration_error
        result.append(choice)
    return result


def mint_xai(cfg, selection):
    import httpx
    key = xai_secret(cfg)
    if not key:
        raise ValueError('Grok credential unavailable')
    # Fixed endpoint, no redirects or shared Hermes cookie jar.
    with httpx.Client(timeout=15, follow_redirects=False) as client:
        response = client.post('https://api.x.ai/v1/realtime/client_secrets',
                               headers={'Authorization': 'Bearer ' + key},
                               json={'expires_after': {'seconds': 300}})
        response.raise_for_status()
        if len(response.content) > 16384:
            raise ValueError('Invalid voice credential response')
        value = response.json()
    if not isinstance(value, dict):
        raise ValueError('Invalid ephemeral credential')
    token, expires = value.get('value'), value.get('expires_at')
    if not isinstance(token, str) or not 1 <= len(token) <= 8192 or token == key or type(expires) not in (int, float) or not time.time() < expires <= time.time() + 600:
        raise ValueError('Invalid ephemeral credential')
    return {**selection, 'token': token, 'websocket_url': 'wss://api.x.ai/v1/realtime', 'expires_at': expires}
