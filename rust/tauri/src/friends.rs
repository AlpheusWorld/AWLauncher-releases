use crate::State;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::{Deserialize, Serialize};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tauri::State as AppState;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    task::{JoinHandle, JoinSet},
};
use tokio_rustls::{
    rustls::{
        pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
        ClientConfig, RootCertStore, ServerConfig,
    },
    TlsAcceptor, TlsConnector,
};

const PREFIX: &str = "AWD1-";
const DOMAIN: &str = "awlauncher.local";
const LEASE: u32 = 3600;
type Shared = Arc<Mutex<ConnectionStatus>>;

#[derive(Default)]
pub struct ConnectionStatus {
    active: bool,
    host: bool,
    ping_ms: Option<u64>,
    last_seen: Option<Instant>,
}
#[derive(Serialize, Deserialize)]
struct Invite {
    addr: SocketAddr,
    cert: String,
    token: String,
}

struct Mapping {
    gateway: igd_next::Gateway,
    port: u16,
    local: SocketAddr,
}
impl Drop for Mapping {
    fn drop(&mut self) {
        let gateway = self.gateway.clone();
        let port = self.port;
        std::thread::spawn(move || {
            let _ = gateway.remove_port(igd_next::PortMappingProtocol::TCP, port);
        });
    }
}
fn replace_session(
    slot: &Mutex<Option<JoinHandle<()>>>,
    task: Option<JoinHandle<()>>,
) -> Result<(), String> {
    if let Some(old) = std::mem::replace(&mut *slot.lock().map_err(|_| "Ошибка состояния")?, task)
    {
        old.abort();
    }
    Ok(())
}
fn reset_status(status: &Shared, host: bool) {
    if let Ok(mut s) = status.lock() {
        *s = ConnectionStatus {
            active: true,
            host,
            ..Default::default()
        };
    }
}
fn seen(status: &Shared, ping: Option<u64>) {
    if let Ok(mut s) = status.lock() {
        s.last_seen = Some(Instant::now());
        if ping.is_some() {
            s.ping_ms = ping;
        }
    }
}
#[tauri::command]
pub async fn stop_world(state: AppState<'_, State>) -> Result<(), String> {
    replace_session(&state.friends, None)?;
    *state.friend_status.lock().map_err(|_| "Ошибка состояния")? = ConnectionStatus::default();
    Ok(())
}
#[tauri::command]
pub fn world_status(state: AppState<'_, State>) -> Result<serde_json::Value, String> {
    let running = state
        .friends
        .lock()
        .map_err(|_| "Ошибка состояния")?
        .as_ref()
        .is_some_and(|h| !h.is_finished());
    let s = state.friend_status.lock().map_err(|_| "Ошибка состояния")?;
    let connected = s
        .last_seen
        .is_some_and(|t| t.elapsed() < Duration::from_secs(15));
    let peers = if connected {
        vec![serde_json::json!({"pingMs":s.ping_ms,"route":"direct"})]
    } else {
        Vec::new()
    };
    Ok(serde_json::json!({"active":running && s.active,"peers":peers,"host":s.host}))
}

fn public_ipv4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    !(ip.is_private()
        || ip.is_loopback()
        || ip.is_link_local()
        || ip.is_unspecified()
        || ip.is_multicast()
        || o[0] == 0
        || o[0] >= 224
        || (o[0] == 100 && (64..=127).contains(&o[1]))
        || (o[0] == 198 && (18..=19).contains(&o[1])))
}
fn open_mapping(local_port: u16) -> Result<(Mapping, SocketAddr), String> {
    let mut addresses = vec![Ipv4Addr::UNSPECIFIED];
    #[cfg(windows)]
    if let Ok(adapters) = ipconfig::get_adapters() {
        for adapter in adapters {
            if adapter.oper_status() != ipconfig::OperStatus::IfOperStatusUp {
                continue;
            }
            for ip in adapter.ip_addresses() {
                if let IpAddr::V4(ip) = ip {
                    if ip.is_private() && !addresses.contains(ip) {
                        addresses.push(*ip);
                    }
                }
            }
        }
    }
    let gateway = addresses
        .into_iter()
        .find_map(|ip| {
            let options = igd_next::SearchOptions {
                bind_addr: (ip, 0).into(),
                timeout: Some(Duration::from_secs(3)),
                ..Default::default()
            };
            igd_next::search_gateway(options).ok()
        })
        .ok_or("Роутер UPnP не найден. Включи UPnP в настройках роутера и проверь VPN")?;
    let external = gateway
        .get_external_ip()
        .map_err(|_| "Роутер не сообщил внешний IP".to_string())?;
    match external {
        IpAddr::V4(ip) if public_ipv4(ip) => {}
        _ => {
            return Err(
                "У роутера нет публичного IPv4: возможно, провайдер использует CGNAT".into(),
            )
        }
    }
    let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    socket
        .connect(gateway.addr)
        .map_err(|_| "Не удалось определить интерфейс роутера")?;
    let local = SocketAddr::new(
        socket.local_addr().map_err(|e| e.to_string())?.ip(),
        local_port,
    );
    let port = gateway
        .add_any_port(
            igd_next::PortMappingProtocol::TCP,
            local,
            LEASE,
            "AWLauncher direct tunnel",
        )
        .map_err(|e| format!("Роутер не открыл порт UPnP: {e}"))?;
    if port == 0 {
        return Err("Роутер вернул некорректный порт".into());
    }
    Ok((
        Mapping {
            gateway,
            port,
            local,
        },
        SocketAddr::new(external, port),
    ))
}
fn server_tls() -> Result<(TlsAcceptor, String), String> {
    let ck = rcgen::generate_simple_self_signed(vec![DOMAIN.into()]).map_err(|e| e.to_string())?;
    let cert = ck.cert.der().to_vec();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(ck.key_pair.serialize_der()));
    let cfg = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![CertificateDer::from(cert.clone())], key)
        .map_err(|e| e.to_string())?;
    Ok((
        TlsAcceptor::from(Arc::new(cfg)),
        URL_SAFE_NO_PAD.encode(cert),
    ))
}
fn client_tls(cert: &str) -> Result<TlsConnector, String> {
    let cert = URL_SAFE_NO_PAD
        .decode(cert)
        .map_err(|_| "Некорректный сертификат приглашения")?;
    if cert.len() > 4096 {
        return Err("Некорректный сертификат приглашения".into());
    }
    let mut roots = RootCertStore::empty();
    roots
        .add(CertificateDer::from(cert))
        .map_err(|_| "Некорректный сертификат приглашения")?;
    let cfg = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok(TlsConnector::from(Arc::new(cfg)))
}
async fn connect(
    inv: &Invite,
    tls: &TlsConnector,
    kind: u8,
) -> Result<(tokio_rustls::client::TlsStream<TcpStream>, u64), String> {
    let tcp = tokio::time::timeout(Duration::from_secs(10), TcpStream::connect(inv.addr))
        .await
        .map_err(|_| "Друг недоступен: проверь UPnP, IPv4 и брандмауэр".to_string())?
        .map_err(|_| "Не удалось подключиться напрямую к другу".to_string())?;
    let _ = tcp.set_nodelay(true);
    let name = ServerName::try_from(DOMAIN)
        .map_err(|e| e.to_string())?
        .to_owned();
    let mut stream = tokio::time::timeout(Duration::from_secs(10), tls.connect(name, tcp))
        .await
        .map_err(|_| "Истекло время защищённого соединения".to_string())?
        .map_err(|_| "Не удалось проверить ключ приглашения".to_string())?;
    let started = Instant::now();
    stream
        .write_all(inv.token.as_bytes())
        .await
        .map_err(|e| e.to_string())?;
    stream.write_all(&[kind]).await.map_err(|e| e.to_string())?;
    let mut status = [0u8; 1];
    tokio::time::timeout(Duration::from_secs(10), stream.read_exact(&mut status))
        .await
        .map_err(|_| "Друг не ответил".to_string())?
        .map_err(|_| "Приглашение неверно или мир закрыт".to_string())?;
    if status != [1] {
        return Err("Мир закрыт или недоступен".into());
    }
    let ping = started.elapsed().as_millis() as u64;
    if kind == b'P' {
        stream
            .write_all(&ping.to_be_bytes())
            .await
            .map_err(|e| e.to_string())?;
    }
    Ok((stream, ping))
}
async fn serve(
    listener: TcpListener,
    port: u16,
    acceptor: TlsAcceptor,
    token: String,
    status: Shared,
    mapping: Option<Mapping>,
) {
    let mut clients = JoinSet::new();
    let mut renew = tokio::time::interval(Duration::from_secs(LEASE as u64 / 2));
    renew.tick().await;
    loop {
        tokio::select! {
            _ = renew.tick(), if mapping.is_some() => {
                if let Some(m) = &mapping {
                    let (g,p,l) = (m.gateway.clone(),m.port,m.local);
                    let result = tokio::task::spawn_blocking(move || g.add_port(igd_next::PortMappingProtocol::TCP,p,l,LEASE,"AWLauncher direct tunnel")).await;
                    if !matches!(result, Ok(Ok(()))) { break; }
                }
            },
            result = listener.accept() => match result {
                Ok((tcp, _)) => {
                    if clients.len() >= 16 { continue; }
                    let _ = tcp.set_nodelay(true);
                    let (acceptor, token, status) = (acceptor.clone(),token.clone(),status.clone());
                    clients.spawn(async move {
                        let Ok(Ok(mut secure)) = tokio::time::timeout(Duration::from_secs(10),acceptor.accept(tcp)).await else { return; };
                        let mut secret = [0u8;64];
                        if !matches!(tokio::time::timeout(Duration::from_secs(10), secure.read_exact(&mut secret)).await,Ok(Ok(_))) { return; }
                        if secret.iter().zip(token.as_bytes()).fold(0u8, |a,(x,y)| a | (x ^ y)) != 0 { return; }
                        let mut kind = [0u8;1];
                        if !matches!(tokio::time::timeout(Duration::from_secs(10),secure.read_exact(&mut kind)).await,Ok(Ok(_))) { return; }
                        if kind == [b'P'] {
                            seen(&status,None);
                            if secure.write_all(&[1]).await.is_err() { return; }
                            let mut measurement = [0u8; 8];
                            if matches!(tokio::time::timeout(Duration::from_secs(2), secure.read_exact(&mut measurement)).await, Ok(Ok(_))) {
                                let ping = u64::from_be_bytes(measurement);
                                if ping <= 60_000 { seen(&status,Some(ping)); }
                            }
                            let _ = secure.shutdown().await;
                            return;
                        }
                        if kind != [b'G'] { return; }
                        let Ok(mut game) = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await else { let _ = secure.write_all(&[0]).await; return; };
                        let _ = game.set_nodelay(true);
                        if secure.write_all(&[1]).await.is_err() { return; }
                        seen(&status,None);
                        let _ = tokio::io::copy_bidirectional(&mut secure,&mut game).await;
                    });
                },
                Err(_) => break,
            },
            Some(_) = clients.join_next(), if !clients.is_empty() => {},
        }
    }
    if let Ok(mut s) = status.lock() {
        s.active = false;
    }
}
#[tauri::command]
pub async fn host_world(port: u16, state: AppState<'_, State>) -> Result<String, String> {
    if port == 0 {
        return Err("Укажи порт мира".into());
    }
    tokio::time::timeout(
        Duration::from_secs(2),
        TcpStream::connect((Ipv4Addr::LOCALHOST, port)),
    )
    .await
    .map_err(|_| "Мир не отвечает".to_string())?
    .map_err(|_| "Не найден открытый мир на этом порту".to_string())?;
    let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0))
        .await
        .map_err(|e| e.to_string())?;
    let local_port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let (acceptor, cert) = server_tls()?;
    let (mapping, addr) = tokio::task::spawn_blocking(move || open_mapping(local_port))
        .await
        .map_err(|e| e.to_string())??;
    let token = format!(
        "{}{}",
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple()
    );
    let ticket = format!(
        "{}{}",
        PREFIX,
        URL_SAFE_NO_PAD.encode(
            serde_json::to_vec(&Invite {
                addr,
                cert,
                token: token.clone()
            })
            .map_err(|e| e.to_string())?
        )
    );
    let shared = state.friend_status.clone();
    replace_session(&state.friends, None)?;
    reset_status(&shared, true);
    let task = tokio::spawn(serve(
        listener,
        port,
        acceptor,
        token,
        shared,
        Some(mapping),
    ));
    replace_session(&state.friends, Some(task))?;
    Ok(ticket)
}
#[tauri::command]
pub async fn join_world(ticket: String, state: AppState<'_, State>) -> Result<String, String> {
    let (addr, task) = join(ticket, state.friend_status.clone()).await?;
    replace_session(&state.friends, Some(task))?;
    Ok(addr)
}
async fn join(ticket: String, status: Shared) -> Result<(String, JoinHandle<()>), String> {
    if ticket.len() > 8192 {
        return Err("Слишком длинное приглашение".into());
    }
    let raw = URL_SAFE_NO_PAD
        .decode(
            ticket
                .trim()
                .strip_prefix(PREFIX)
                .ok_or("Нужно новое прямое приглашение AWD1")?,
        )
        .map_err(|_| "Некорректное приглашение")?;
    let invite: Invite = serde_json::from_slice(&raw).map_err(|_| "Некорректное приглашение")?;
    if invite.addr.port() == 0
        || invite.token.len() != 64
        || !invite.token.bytes().all(|b| b.is_ascii_hexdigit())
        || invite.addr.ip().is_unspecified()
        || invite.addr.ip().is_multicast()
    {
        return Err("Некорректное приглашение".into());
    }
    let tls = client_tls(&invite.cert)?;
    let (_, ping) = connect(&invite, &tls, b'P').await?;
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .map_err(|e| e.to_string())?;
    let addr = listener
        .local_addr()
        .map_err(|e| e.to_string())?
        .to_string();
    reset_status(&status, false);
    seen(&status, Some(ping));
    let task = tokio::spawn(async move {
        let mut clients = JoinSet::new();
        let mut check = tokio::time::interval(Duration::from_secs(5));
        check.tick().await;
        loop {
            tokio::select! {
                _ = check.tick() => {
                    if let Ok((_, ping)) = connect(&invite,&tls,b'P').await { seen(&status,Some(ping)); }
                },
                result = listener.accept() => match result {
                    Ok((mut local,_)) => {
                        if clients.len() >= 16 { continue; }
                        let _ = local.set_nodelay(true);
                        let inv = Invite { addr:invite.addr, cert:invite.cert.clone(), token:invite.token.clone() };
                        let tls = tls.clone();
                        clients.spawn(async move {
                            let Ok((mut remote, _)) = connect(&inv,&tls,b'G').await else { return; };
                            let _ = tokio::io::copy_bidirectional(&mut local,&mut remote).await;
                        });
                    },
                    Err(_) => break,
                },
                Some(_) = clients.join_next(), if !clients.is_empty() => {},
            }
        }
        if let Ok(mut s) = status.lock() {
            s.active = false;
        }
    });
    Ok((addr, task))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "opens a temporary UPnP mapping on the current router"]
    fn router_can_open_and_remove_temporary_mapping() {
        let listener = std::net::TcpListener::bind("0.0.0.0:0").unwrap();
        let (mapping, addr) = open_mapping(listener.local_addr().unwrap().port()).unwrap();
        assert_ne!(addr.port(), 0);
        mapping
            .gateway
            .remove_port(igd_next::PortMappingProtocol::TCP, mapping.port)
            .unwrap();
        drop(mapping);
    }
    #[test]
    fn direct_tls_tunnel_transfers_bytes_and_rejects_bad_token() {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let game = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let port = game.local_addr().unwrap().port();
            let game_task = tokio::spawn(async move {
                while let Ok((mut s, _)) = game.accept().await {
                    tokio::spawn(async move {
                        let mut b = [0; 64];
                        if let Ok(n) = s.read(&mut b).await {
                            let _ = s.write_all(&b[..n]).await;
                        }
                    });
                }
            });
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let peer = listener.local_addr().unwrap();
            let (tls, cert) = server_tls().unwrap();
            let token = "a".repeat(64);
            let host_status = Arc::new(Mutex::new(ConnectionStatus::default()));
            let host = tokio::spawn(serve(
                listener,
                port,
                tls,
                token.clone(),
                host_status.clone(),
                None,
            ));
            let bad = Invite {
                addr: peer,
                cert: cert.clone(),
                token: "b".repeat(64),
            };
            assert!(connect(&bad, &client_tls(&cert).unwrap(), b'P')
                .await
                .is_err());
            let inv = Invite {
                addr: peer,
                cert,
                token,
            };
            let ticket = format!(
                "{}{}",
                PREFIX,
                URL_SAFE_NO_PAD.encode(serde_json::to_vec(&inv).unwrap())
            );
            let (addr, task) = join(ticket, Arc::new(Mutex::new(ConnectionStatus::default())))
                .await
                .unwrap();
            tokio::time::timeout(Duration::from_secs(2), async {
                loop {
                    if host_status.lock().unwrap().ping_ms.is_some() {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .expect("host receives ping from guest");
            let mut client = TcpStream::connect(addr).await.unwrap();
            client.write_all(b"minecraft tunnel test").await.unwrap();
            let mut output = [0; 21];
            tokio::time::timeout(Duration::from_secs(5), client.read_exact(&mut output))
                .await
                .unwrap()
                .unwrap();
            assert_eq!(&output, b"minecraft tunnel test");
            task.abort();
            host.abort();
            game_task.abort();
        });
    }
}
