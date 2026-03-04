//! Ratatui rendering functions for all TUI screens.

use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Paragraph, Wrap};
use ratatui::Frame;

use crate::app::{App, Screen};

/// Main draw entry point, dispatches to the appropriate screen renderer.
pub fn draw(f: &mut Frame, app: &App) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Min(5),    // main content
            Constraint::Length(3), // status bar
        ])
        .split(f.area());

    match app.screen {
        Screen::Welcome => draw_welcome(f, app, chunks[0]),
        Screen::Wallet => draw_wallet(f, app, chunks[0]),
        Screen::SendAddress => draw_send_address(f, app, chunks[0]),
        Screen::SendAmount => draw_send_amount(f, app, chunks[0]),
        Screen::SendFeeRate => draw_send_fee_rate(f, app, chunks[0]),
        Screen::SendConfirm => draw_send_confirm(f, app, chunks[0]),
        Screen::SendDisplay => draw_send_display(f, app, chunks[0]),
        Screen::ReceiveInput => draw_receive_input(f, app, chunks[0]),
        Screen::ReceiveConfirm => draw_receive_confirm(f, app, chunks[0]),
        Screen::History => draw_history(f, app, chunks[0]),
    }

    draw_status_bar(f, app, chunks[1]);
}

fn draw_status_bar(f: &mut Frame, app: &App, area: Rect) {
    let status = Paragraph::new(app.status_message.as_str())
        .style(Style::default().fg(Color::Yellow))
        .block(Block::default().borders(Borders::ALL).title(" Status "));
    f.render_widget(status, area);
}

fn draw_welcome(f: &mut Frame, app: &App, area: Rect) {
    let mut lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  BURNER WALLET COMPANION",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  Network: {}", network_name(app.network)),
            Style::default().fg(Color::White),
        )),
        Line::from(Span::styled(
            format!("  Esplora: {}", app.esplora_url),
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(""),
    ];

    if app.mnemonic_phrase.is_some() {
        lines.push(Line::from(Span::styled(
            "  Wallet initialized. Loading...",
            Style::default().fg(Color::Green),
        )));
    } else {
        lines.push(Line::from(Span::styled(
            "  No mnemonic provided.",
            Style::default().fg(Color::White),
        )));
        lines.push(Line::from(""));
        lines.push(Line::from(Span::styled(
            "  [g] Generate new 12-word mnemonic",
            Style::default().fg(Color::Green),
        )));
        lines.push(Line::from(Span::styled(
            "  [i] Import existing mnemonic",
            Style::default().fg(Color::Green),
        )));
        lines.push(Line::from(Span::styled(
            "  [q] Quit",
            Style::default().fg(Color::Red),
        )));

        if !app.input_buffer.is_empty() {
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                format!("  > {}_", app.input_buffer),
                Style::default().fg(Color::Yellow),
            )));
        }
    }

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" Welcome "));
    f.render_widget(widget, area);
}

fn draw_wallet(f: &mut Frame, app: &App, area: Rect) {
    let mnemonic_display = app
        .mnemonic_phrase
        .as_ref()
        .map(|m| m.to_string())
        .unwrap_or_else(|| String::from("(none)"));

    let balance_display = if app.synced {
        format!("{} sats", app.balance_sats)
    } else {
        String::from("(not synced)")
    };

    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  WALLET",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(vec![
            Span::styled("  Network:  ", Style::default().fg(Color::DarkGray)),
            Span::styled(network_name(app.network), Style::default().fg(Color::White)),
        ]),
        Line::from(vec![
            Span::styled("  Balance:  ", Style::default().fg(Color::DarkGray)),
            Span::styled(balance_display, Style::default().fg(Color::Green)),
        ]),
        Line::from(vec![
            Span::styled("  Address:  ", Style::default().fg(Color::DarkGray)),
            Span::styled(&app.receive_address, Style::default().fg(Color::Yellow)),
        ]),
        Line::from(""),
        Line::from(Span::styled(
            format!("  Mnemonic: {}", mnemonic_display),
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  ---- Actions ----",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(Span::styled(
            "  [y] Sync wallet",
            Style::default().fg(Color::Green),
        )),
        Line::from(Span::styled(
            "  [s] Send (build PSBT)",
            Style::default().fg(Color::Green),
        )),
        Line::from(Span::styled(
            "  [r] Receive signed PSBT",
            Style::default().fg(Color::Green),
        )),
        Line::from(Span::styled(
            "  [h] Transaction history",
            Style::default().fg(Color::Green),
        )),
        Line::from(Span::styled("  [q] Quit", Style::default().fg(Color::Red))),
    ];

    let widget = Paragraph::new(lines).block(
        Block::default()
            .borders(Borders::ALL)
            .title(" Burner Wallet "),
    );
    f.render_widget(widget, area);
}

fn draw_send_address(f: &mut Frame, app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  SEND - Step 1/4: Recipient Address",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Enter the recipient Bitcoin address:",
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  > {}_", app.input_buffer),
            Style::default().fg(Color::Yellow),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  [Enter] Confirm    [Esc] Cancel",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" Send "));
    f.render_widget(widget, area);
}

fn draw_send_amount(f: &mut Frame, app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  SEND - Step 2/4: Amount",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  To: {}", app.send_recipient),
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Enter amount in satoshis:",
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  > {}_", app.input_buffer),
            Style::default().fg(Color::Yellow),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  [Enter] Confirm    [Esc] Cancel",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" Send "));
    f.render_widget(widget, area);
}

fn draw_send_fee_rate(f: &mut Frame, app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  SEND - Step 3/4: Fee Rate",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  To: {}", app.send_recipient),
            Style::default().fg(Color::White),
        )),
        Line::from(Span::styled(
            format!("  Amount: {} sats", app.send_amount),
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Enter fee rate in sat/vB:",
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  > {}_", app.input_buffer),
            Style::default().fg(Color::Yellow),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  [Enter] Confirm    [Esc] Cancel",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" Send "));
    f.render_widget(widget, area);
}

fn draw_send_confirm(f: &mut Frame, app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  SEND - Step 4/4: Confirm",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Review your transaction:",
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(vec![
            Span::styled("  Recipient:  ", Style::default().fg(Color::DarkGray)),
            Span::styled(&app.send_recipient, Style::default().fg(Color::Yellow)),
        ]),
        Line::from(vec![
            Span::styled("  Amount:     ", Style::default().fg(Color::DarkGray)),
            Span::styled(
                format!("{} sats", app.send_amount),
                Style::default().fg(Color::Green),
            ),
        ]),
        Line::from(vec![
            Span::styled("  Fee rate:   ", Style::default().fg(Color::DarkGray)),
            Span::styled(
                format!("{} sat/vB", app.send_fee_rate),
                Style::default().fg(Color::White),
            ),
        ]),
        Line::from(""),
        Line::from(Span::styled(
            "  [Enter] Build PSBT    [Esc] Cancel",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let widget = Paragraph::new(lines).block(
        Block::default()
            .borders(Borders::ALL)
            .title(" Confirm Send "),
    );
    f.render_widget(widget, area);
}

fn draw_send_display(f: &mut Frame, app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  UNSIGNED PSBT",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Copy the hex below and transfer to the signer for signing.",
            Style::default().fg(Color::White),
        )),
        Line::from(Span::styled(
            "  After signing, use Receive (r) to import the signed PSBT.",
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  Length: {} bytes", app.send_psbt_hex.len() / 2),
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(""),
    ];

    // Split PSBT hex into wrapped lines for display
    let hex_text = Paragraph::new(app.send_psbt_hex.as_str())
        .style(Style::default().fg(Color::Yellow))
        .wrap(Wrap { trim: false });

    let inner_chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(10), // header
            Constraint::Min(4),     // psbt hex
            Constraint::Length(2),  // footer
        ])
        .split(area);

    let header =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" PSBT "));
    f.render_widget(header, inner_chunks[0]);

    let hex_block = hex_text.block(
        Block::default()
            .borders(Borders::LEFT | Borders::RIGHT)
            .title(""),
    );
    f.render_widget(hex_block, inner_chunks[1]);

    let footer = Paragraph::new("  [Esc] Back to wallet")
        .style(Style::default().fg(Color::DarkGray))
        .block(Block::default().borders(Borders::LEFT | Borders::RIGHT | Borders::BOTTOM));
    f.render_widget(footer, inner_chunks[2]);
}

fn draw_receive_input(f: &mut Frame, app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  RECEIVE SIGNED PSBT",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Paste the signed PSBT hex from the signer:",
            Style::default().fg(Color::White),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  > {}_", app.input_buffer),
            Style::default().fg(Color::Yellow),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  (input length: {} chars)", app.input_buffer.len()),
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  [Enter] Process    [Esc] Cancel",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" Receive "));
    f.render_widget(widget, area);
}

fn draw_receive_confirm(f: &mut Frame, app: &App, area: Rect) {
    let mut lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  BROADCAST TRANSACTION",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Transaction is finalized and ready to broadcast.",
            Style::default().fg(Color::Green),
        )),
        Line::from(""),
        Line::from(Span::styled(
            format!("  TX hex length: {} bytes", app.receive_tx_hex.len() / 2),
            Style::default().fg(Color::White),
        )),
        Line::from(""),
    ];

    if !app.receive_txid.is_empty() {
        lines.push(Line::from(Span::styled(
            format!("  TXID: {}", app.receive_txid),
            Style::default().fg(Color::Green),
        )));
        lines.push(Line::from(""));
    }

    lines.push(Line::from(Span::styled(
        format!("  Esplora: {}", app.esplora_url),
        Style::default().fg(Color::DarkGray),
    )));
    lines.push(Line::from(""));
    lines.push(Line::from(Span::styled(
        "  [Enter] Broadcast    [Esc] Cancel",
        Style::default().fg(Color::DarkGray),
    )));

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" Broadcast "));
    f.render_widget(widget, area);
}

fn draw_history(f: &mut Frame, _app: &App, area: Rect) {
    let lines = vec![
        Line::from(""),
        Line::from(Span::styled(
            "  TRANSACTION HISTORY",
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  No transactions yet.",
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  Transaction history requires wallet persistence,",
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(Span::styled(
            "  which will be added in a future milestone.",
            Style::default().fg(Color::DarkGray),
        )),
        Line::from(""),
        Line::from(Span::styled(
            "  [Esc] Back to wallet",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let widget =
        Paragraph::new(lines).block(Block::default().borders(Borders::ALL).title(" History "));
    f.render_widget(widget, area);
}

fn network_name(network: bitcoin::Network) -> String {
    match network {
        bitcoin::Network::Bitcoin => String::from("mainnet"),
        bitcoin::Network::Testnet => String::from("testnet"),
        bitcoin::Network::Signet => String::from("signet"),
        bitcoin::Network::Regtest => String::from("regtest"),
        _ => String::from("unknown"),
    }
}
