#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;
use contract_ffi::contract_api::{get_arg, revert, set_action_threshold};
use contract_ffi::value::account::{ActionType, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let key_management_threshold: Weight = get_arg(0).unwrap().unwrap();
    let deploy_threshold: Weight = get_arg(1).unwrap().unwrap();

    if key_management_threshold != Weight::new(0) {
        set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_else(|_| revert(100));
    }

    if deploy_threshold != Weight::new(0) {
        set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_else(|_| revert(200));
    }
}
