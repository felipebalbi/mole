//! CLI front-end for `mole-loader`.
//!
//! This is a scaffold; full clap surface lands in a follow-up commit
//! once the decoder and serial-port transport in `mole-loader` are in.

use color_eyre::eyre::Result;

fn main() -> Result<()> {
    color_eyre::install()?;
    eprintln!("mole-loader: scaffold (no functionality yet)");
    Ok(())
}
