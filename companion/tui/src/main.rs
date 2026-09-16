//! Burner Wallet Companion TUI -- interactive terminal interface for the
//! online companion. Manages wallet creation, PSBT construction, and
//! transaction broadcasting via a ratatui-based terminal UI.

use std::io;
use std::path::PathBuf;

use clap::{Parser, ValueEnum};
use crossterm::event::{self, Event, KeyCode, KeyEventKind, KeyModifiers};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use ratatui::backend::CrosstermBackend;
use ratatui::Terminal;

use bitcoin::Network;

use burner_companion_tui::app::{App, Screen};
use burner_companion_tui::ui;

#[derive(Parser)]
#[command(
    name = "burner-companion",
    about = "Burner Wallet companion TUI -- air-gapped Bitcoin wallet companion"
)]
struct Cli {
    /// Bitcoin network
    #[arg(long, value_enum, default_value_t = NetworkArg::Testnet)]
    network: NetworkArg,

    /// Path to a file containing the BIP39 mnemonic phrase (12 or 24 words).
    ///
    /// The phrase is read from a file rather than the command line so it
    /// never appears in shell history or `ps` output. Development aid only;
    /// prefer importing interactively.
    #[arg(long, value_name = "PATH")]
    mnemonic_file: Option<PathBuf>,

    /// Esplora server URL (default per network)
    #[arg(long)]
    esplora: Option<String>,
}

/// Networks accepted on the command line. Unknown values are rejected by
/// clap instead of silently falling back to testnet.
#[derive(Clone, Copy, Debug, ValueEnum)]
enum NetworkArg {
    Mainnet,
    Testnet,
    Signet,
    Regtest,
}

impl From<NetworkArg> for Network {
    fn from(n: NetworkArg) -> Self {
        match n {
            NetworkArg::Mainnet => Network::Bitcoin,
            NetworkArg::Testnet => Network::Testnet,
            NetworkArg::Signet => Network::Signet,
            NetworkArg::Regtest => Network::Regtest,
        }
    }
}

/// Put the terminal back into a usable state (raw mode off, main screen).
fn restore_terminal() {
    let _ = disable_raw_mode();
    let _ = execute!(io::stdout(), LeaveAlternateScreen);
}

fn main() -> io::Result<()> {
    let cli = Cli::parse();

    let network: Network = cli.network.into();

    let esplora_url = cli.esplora.unwrap_or_else(|| match network {
        Network::Bitcoin => String::from("https://mempool.space/api"),
        Network::Signet => String::from("https://mempool.space/signet/api"),
        Network::Regtest => String::from("http://127.0.0.1:3002"),
        _ => String::from("https://mempool.space/testnet/api"),
    });

    let mnemonic = match cli.mnemonic_file {
        Some(path) => Some(std::fs::read_to_string(&path).map_err(|e| {
            io::Error::new(
                e.kind(),
                format!("cannot read mnemonic file {}: {}", path.display(), e),
            )
        })?),
        None => None,
    };

    let mut app = App::new(network, esplora_url, mnemonic.as_deref().map(str::trim));

    // A panic anywhere (BDK, ratatui, crossterm) must not leave the shell
    // in raw mode on the alternate screen.
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        restore_terminal();
        default_hook(info);
    }));

    // Setup terminal
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    // Run the app
    let result = run_app(&mut terminal, &mut app);

    // Restore terminal
    restore_terminal();
    terminal.show_cursor()?;

    if let Err(e) = result {
        eprintln!("Application error: {}", e);
    }

    Ok(())
}

fn run_app(terminal: &mut Terminal<CrosstermBackend<io::Stdout>>, app: &mut App) -> io::Result<()> {
    loop {
        terminal.draw(|f| ui::draw(f, app))?;

        if let Event::Key(key) = event::read()? {
            // Only handle key press events (not release/repeat)
            if key.kind != KeyEventKind::Press {
                continue;
            }

            // Global: Ctrl-C always quits
            if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
                app.should_quit = true;
            } else {
                handle_key(app, key.code);
            }
        }

        if app.should_quit {
            break;
        }
    }
    Ok(())
}

fn handle_key(app: &mut App, key: KeyCode) {
    match app.screen {
        Screen::Welcome => handle_welcome_key(app, key),
        Screen::Wallet => handle_wallet_key(app, key),
        Screen::SendAddress => handle_input_key(app, key, InputContext::SendAddress),
        Screen::SendAmount => handle_input_key(app, key, InputContext::SendAmount),
        Screen::SendFeeRate => handle_input_key(app, key, InputContext::SendFeeRate),
        Screen::SendConfirm => handle_send_confirm_key(app, key),
        Screen::SendDisplay => handle_send_display_key(app, key),
        Screen::ReceiveInput => handle_input_key(app, key, InputContext::ReceiveInput),
        Screen::ReceiveConfirm => handle_receive_confirm_key(app, key),
        Screen::History => handle_history_key(app, key),
    }
}

/// Tracks which input field we are editing, so the generic input handler
/// knows which confirmation action to call on Enter.
enum InputContext {
    SendAddress,
    SendAmount,
    SendFeeRate,
    ReceiveInput,
}

fn handle_welcome_key(app: &mut App, key: KeyCode) {
    // If we are in import mode (input buffer is being used for mnemonic input)
    if !app.input_buffer.is_empty() || app.status_message.contains("mnemonic") {
        match key {
            KeyCode::Enter => {
                let phrase = app.input_buffer.clone();
                app.init_wallet_from_phrase(&phrase);
                if app.screen != Screen::Wallet {
                    // Still on welcome -- clear for retry
                    app.input_buffer.clear();
                }
            }
            KeyCode::Esc => {
                app.input_buffer.clear();
                app.status_message.clear();
            }
            KeyCode::Backspace => {
                app.input_buffer.pop();
            }
            KeyCode::Char(c) => {
                app.input_buffer.push(c);
            }
            _ => {}
        }
        return;
    }

    match key {
        KeyCode::Char('g') => {
            app.generate_new_wallet();
        }
        KeyCode::Char('i') => {
            app.status_message = String::from("Type your mnemonic phrase and press Enter:");
            // input_buffer will be used for mnemonic entry
        }
        KeyCode::Char('q') => {
            app.should_quit = true;
        }
        _ => {}
    }
}

fn handle_wallet_key(app: &mut App, key: KeyCode) {
    match key {
        KeyCode::Char('q') => app.should_quit = true,
        KeyCode::Char('s') => app.start_send(),
        KeyCode::Char('r') => app.start_receive(),
        KeyCode::Char('h') => {
            app.screen = Screen::History;
            app.status_message.clear();
        }
        KeyCode::Char('y') => app.sync_wallet(),
        _ => {}
    }
}

fn handle_input_key(app: &mut App, key: KeyCode, ctx: InputContext) {
    match key {
        KeyCode::Enter => match ctx {
            InputContext::SendAddress => app.confirm_send_address(),
            InputContext::SendAmount => app.confirm_send_amount(),
            InputContext::SendFeeRate => app.confirm_send_fee_rate(),
            InputContext::ReceiveInput => app.process_signed_psbt(),
        },
        KeyCode::Esc => app.go_home(),
        KeyCode::Backspace => {
            app.input_buffer.pop();
        }
        KeyCode::Char(c) => {
            app.input_buffer.push(c);
        }
        _ => {}
    }
}

fn handle_send_confirm_key(app: &mut App, key: KeyCode) {
    match key {
        KeyCode::Enter => app.build_send_psbt(),
        KeyCode::Esc => app.go_home(),
        _ => {}
    }
}

fn handle_send_display_key(app: &mut App, key: KeyCode) {
    if key == KeyCode::Esc {
        app.go_home();
    }
}

fn handle_receive_confirm_key(app: &mut App, key: KeyCode) {
    match key {
        KeyCode::Enter => app.broadcast_transaction(),
        KeyCode::Esc => app.go_home(),
        _ => {}
    }
}

fn handle_history_key(app: &mut App, key: KeyCode) {
    match key {
        KeyCode::Esc | KeyCode::Char('q') => app.go_home(),
        _ => {}
    }
}
