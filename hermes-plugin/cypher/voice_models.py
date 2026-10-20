"""Owner-scoped voice choices for direct phone-key calls."""
import hashlib
import json

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


def choices(cfg, mobile=None, owner=None, profile=None):
    result = []
    for provider in PROVIDERS:
        value = provider_selection(mobile,owner,profile,cfg,provider) if mobile else configuration(cfg,provider)
        label = {'gemini': 'Gemini Live', 'grok': 'Grok Voice', 'openai': 'OpenAI Realtime'}[provider]
        choice={'id': provider, 'label': label,
                'model': value['model'], 'voice': value['voice'], 'voices': list(VOICES[provider]),
                'personality':value['personality'], 'personalities':[{'id':k,'label':k.title()} for k in PERSONALITIES], 'available': True,
                'configured': True, 'vision': value['capabilities']['vision']}
        result.append(choice)
    return result
