#![no_std]

extern crate alloc;

use alloc::{format, string::String};

use contract::{
    contract_api::{account, runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key, URef, U512};

const PURSE_MAIN: &str = "purse:main";
const PURSE_TRANSFER_RESULT: &str = "purse_transfer_result";
const MAIN_PURSE_BALANCE: &str = "main_purse_balance";

#[repr(u16)]
enum Args {
    SourcePurse = 0,
    DestinationPurse = 1,
    Amount = 2,
}

#[repr(u16)]
enum CustomError {
    InvalidSourcePurseKey = 103,
    UnexpectedSourcePurseKeyVariant = 104,
    InvalidDestinationPurseKey = 105,
    UnexpectedDestinationPurseKeyVariant = 106,
    UnableToGetBalance = 107,
}

#[no_mangle]
pub extern "C" fn call() {
    let main_purse: URef = account::get_main_purse();
    // add or update `main_purse` if it doesn't exist already
    runtime::put_key(PURSE_MAIN, Key::from(main_purse));

    let src_purse_name: String = runtime::get_arg(Args::SourcePurse as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let src_purse_key = runtime::get_key(&src_purse_name)
        .unwrap_or_revert_with(ApiError::User(CustomError::InvalidSourcePurseKey as u16));

    let src_purse = match src_purse_key.as_uref() {
        Some(uref) => uref,
        None => runtime::revert(ApiError::User(
            CustomError::UnexpectedSourcePurseKeyVariant as u16,
        )),
    };
    let dst_purse_name: String = runtime::get_arg(Args::DestinationPurse as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let dst_purse = if !runtime::has_key(&dst_purse_name) {
        // If `dst_purse_name` is not in known urefs list then create a new purse
        let purse = system::create_purse();
        // and save it in known urefs
        runtime::put_key(&dst_purse_name, purse.into());
        purse
    } else {
        let destination_purse_key = runtime::get_key(&dst_purse_name).unwrap_or_revert_with(
            ApiError::User(CustomError::InvalidDestinationPurseKey as u16),
        );
        match destination_purse_key.as_uref() {
            Some(uref) => *uref,
            None => runtime::revert(ApiError::User(
                CustomError::UnexpectedDestinationPurseKeyVariant as u16,
            )),
        }
    };
    let amount: U512 = runtime::get_arg(Args::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let transfer_result = system::transfer_from_purse_to_purse(*src_purse, dst_purse, amount);

    // Assert is done here
    let final_balance = system::get_balance(main_purse)
        .unwrap_or_revert_with(ApiError::User(CustomError::UnableToGetBalance as u16));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_key: Key = storage::new_turef(result).into();
    runtime::put_key(PURSE_TRANSFER_RESULT, result_key);
    runtime::put_key(MAIN_PURSE_BALANCE, storage::new_turef(final_balance).into());
}
