import asyncio
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import httpx
import pytest
from fastapi.testclient import TestClient
from app.analysis_engine import Candle, ema, rsi, macd, bollinger, signals, combined_signal
from app.backtesting import backtest, chronological_walk_forward, BacktestTrade
from app.learning import CandidateReport, VersionRegistry
from app.risk_engine import OrderLedger, RiskLimits, RiskRejected
from app.deriv_official import DerivCredentials, DerivOfficialClient, DerivError, official_ws_url, buy_confirmed_proposal, public_candles
from app.main import create_app

TOKEN = 'token_de_testes_com_mais_de_24_caracteres'
HEADERS = {'Authorization':f'Bearer {TOKEN}'}


def history(n=650, slope=.0003):
    begin=datetime(2026, 1, 1, tzinfo=timezone.utc)
    return [Candle(begin+timedelta(minutes=i), 100+i*slope,100+i*slope+.01,100+i*slope-.01,100+i*slope+.002) for i in range(n)]


def test_indicator_math_and_validation():
    candles=history(250)
    assert ema([1,2,3,4,5],3)[-1]==4
    assert rsi([2]*20)[-1]==50
    assert 0 <= rsi([float(i) for i in range(20)])[-1] <= 100
    assert bollinger([1]*30)[-1] == (1,1,1)
    assert macd([float(i) for i in range(70)])[0][-1] is not None
    assert signals(candles)['tendencia_ema']=='CALL'
    assert combined_signal(candles,('tendencia_ema','rsi_macd')) in (None,'CALL')
    with pytest.raises(ValueError): signals([candles[0],candles[0]])
    with pytest.raises(ValueError): Candle(candles[0].timestamp,5,4,3,6)


def test_backtest_has_no_lookahead_and_no_overlap():
    data=history(650)
    first=backtest(data[:400], 'tendencia_ema',duration_minutes=5)
    full=backtest(data, 'tendencia_ema',duration_minutes=5)
    assert all(t.enter_at>t.signal_at and t.exit_at>=t.enter_at for t in full.trades)
    assert all(a.exit_at<=b.signal_at for a,b in zip(full.trades,full.trades[1:]))
    assert [t for t in full.trades if t.exit_at <= data[399].timestamp.isoformat()] == list(first.trades)
    walk=chronological_walk_forward(data,'tendencia_ema',duration_minutes=5)
    assert 'training' in walk and 'test' in walk and 'validation' in walk
    assert walk['test']['net_units'] >= 0


def test_version_gate_and_rollback(tmp_path):
    reg=VersionRegistry(str(tmp_path/'registry.db'))
    def report(version, n=130, net=4.5):
        return CandidateReport('ema',version,n,net,3.,n,net,4.)
    assert reg.register(report('one'))['state']=='VALIDATED_DEMO_CANDIDATE'
    reg.promote_demo('ema','one')
    assert reg.register(report('two'))['state']=='VALIDATED_DEMO_CANDIDATE'
    reg.promote_demo('ema','two')
    reg.rollback_demo('ema','one')
    states={x['version']:x['state'] for x in reg.list_versions()}
    assert states['one']=='DEMO_ACTIVE' and states['two']=='ROLLED_BACK'
    assert reg.register(report('bad', n=20))['state']=='REJECTED'
    with pytest.raises(ValueError): reg.promote_demo('ema','bad')


def reserve(ledger, request_id, **updates):
    options=dict(request_id=request_id,account_type='REAL',account_id='real-1',asset='R_50',
                 balance='10000',stake='50.00',payout_percent='82',connected=True,
                 market_fresh=True,open_positions_reconciled=True)
    options.update(updates)
    return ledger.reserve(**options)


def test_ledger_duplicate_and_real_mode_separation(tmp_path):
    l=OrderLedger(str(tmp_path/'risk.db'))
    assert reserve(l,'x')['state']=='PENDING'
    with pytest.raises(RiskRejected,match='duplicada'): reserve(l,'x')
    with pytest.raises(RiskRejected,match='simultâneas'): reserve(l,'y', asset='R_100')
    # Conta demo não compartilha a exposição real.
    assert reserve(l,'z',account_type='DEMO')['state']=='PENDING'
    l.mark_open('x','contract-1')
    l.settle('x','-50.00')
    assert reserve(l,'y',asset='R_100')['state']=='PENDING'
    assert len(l.snapshot('REAL','real-1'))==2
    assert len(l.snapshot('DEMO','real-1'))==1


def test_ledger_unknown_orders_are_not_resent(tmp_path):
    l=OrderLedger(str(tmp_path/'risk.db'))
    reserve(l,'x')
    l.mark_unknown('x')
    with pytest.raises(RiskRejected): reserve(l,'x')
    with pytest.raises(RiskRejected): reserve(l,'another')
    l.settle('x','-50.00')
    with pytest.raises(RiskRejected): l.settle('x','-50.00')


def test_ledger_stops_on_losses_and_risk(tmp_path):
    l=OrderLedger(str(tmp_path/'risk.db'))
    for i in range(4):
        reserve(l,str(i), asset='R_50', balance=str(10000-i*50))
        l.mark_open(str(i),str(i)); l.settle(str(i),'-50')
    with pytest.raises(RiskRejected,match='consecutivas'):
        reserve(l,'last',balance='9800')
    with pytest.raises(RiskRejected,match='Payout'):
        reserve(l,'low',payout_percent='79')
    with pytest.raises(RiskRejected,match='confirmados'):
        reserve(l,'offline',connected=False)
    with pytest.raises(ValueError): RiskLimits(max_stake_percent=Decimal('9'))


def test_deriv_url_policy_and_no_buy_without_gate():
    demo='wss://api.derivws.com/trading/v1/options/ws/demo?otp=123456789'
    real='wss://api.derivws.com/trading/v1/options/ws/real?otp=abcdefghi'
    assert official_ws_url(demo,'demo')
    assert official_ws_url(real,'real')
    assert not official_ws_url(real,'demo')
    assert not official_ws_url(real+'#bad','real')
    assert not official_ws_url('wss://api.derivws.com.evil/trading/v1/options/ws/real?otp=abcdefghi','real')
    async def denied():
        c=DerivOfficialClient(DerivCredentials('myappid','testtoken'))
        return await buy_confirmed_proposal(c,'aaa','real',proposal_id='x'*32,max_price=Decimal('50'))
    with pytest.raises(DerivError,match='desativada'):
        asyncio.run(denied())


def test_deriv_mock_http_does_not_switch_account_type():
    requests=[]
    def handler(request):
        requests.append(request)
        if request.method=='GET':
            return httpx.Response(200,json={'data':[{'account_id':'demo-x','account_type':'demo'}, {'account_id':'real-x','account_type':'real'}]})
        return httpx.Response(200,json={'data':{'url':'wss://api.derivws.com/trading/v1/options/ws/demo?otp=123456789'}})
    async def run():
        client=DerivOfficialClient(DerivCredentials('myappid','token-test'),transport=httpx.MockTransport(handler))
        assert len(await client.accounts())==2
        assert (await client.otp_websocket('demo-x','demo')).endswith('123456789')
        with pytest.raises(DerivError,match='não oficial'):
            await client.otp_websocket('real-x','real')
    asyncio.run(run())
    assert all(r.headers.get('deriv-app-id')=='myappid' for r in requests)


def test_research_api_auth_and_no_order_endpoints(tmp_path):
    api=create_app(str(tmp_path/'test.db'),TOKEN)
    routes={x.path for x in api.routes}
    assert '/api/research/backtest' in routes
    assert '/api/deriv/accounts' in routes
    assert '/api/deriv/buy' not in routes and '/api/orders/execute' not in routes
    with TestClient(api) as c:
        assert c.get('/api/research/strategies').status_code == 401
        names=c.get('/api/research/strategies',headers=HEADERS).json()
        assert len(names)==6
        assert c.get('/api/deriv/connection',headers=HEADERS).json()['real_orders_enabled'] is False
        assert c.get('/api/deriv/accounts',headers=HEADERS).status_code == 503
        data=dict(candles=[dict(timestamp=x.timestamp.isoformat(),open=x.open,high=x.high,low=x.low,close=x.close) for x in history(650)],
                  strategy='tendencia_ema',duration_minutes=1,payout=.82)
        r=c.post('/api/research/backtest',json=data,headers=HEADERS)
        assert r.status_code==200, r.text
        assert 'test' in r.json()


def test_market_observer_persists_without_order_permissions(tmp_path):
    from app.observer import ObservationStore
    db=ObservationStore(str(tmp_path/'obs.db'))
    candle={'epoch':1760000000,'open':'100.0','high':'102.0','low':'99.0','close':'101.0'}
    assert db.put('R_50',[candle]) == 1
    assert db.put('R_50',[candle]) == 1  # registro duplicado ignorado no banco
    import sqlite3
    with sqlite3.connect(db.path) as con:
        assert con.execute('SELECT COUNT(*) FROM market_observations').fetchone()[0]==1


def test_market_observer_rejects_bad_configuration(tmp_path):
    from app.observer import observer_loop
    with pytest.raises(ValueError,match='Selecione ativos'):
        asyncio.run(observer_loop(str(tmp_path/'obs.db'),[],interval_seconds=60))
    with pytest.raises(ValueError,match='Intervalo'):
        asyncio.run(observer_loop(str(tmp_path/'obs.db'),['R_50'],interval_seconds=10))


def test_real_order_rejects_even_if_callsite_passes_gate_true(monkeypatch):
    monkeypatch.delenv("VELALAB_REAL_ORDERS_VALIDATED",raising=False)
    async def attempt():
        c=DerivOfficialClient(DerivCredentials("myappid", "token-test"))
        return await buy_confirmed_proposal(c,"real-account","real",proposal_id="x"*32,
                                           max_price=Decimal("1"), gate=True)
    with pytest.raises(DerivError, match="bloqueadas"):
        asyncio.run(attempt())
