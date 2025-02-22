/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

use error_support::{ErrorHandling, GetErrorHandling};

/// Result enum for the public interface
pub type ApiResult<T> = std::result::Result<T, TabsApiError>;
/// Result enum for internal functions
pub type Result<T> = std::result::Result<T, Error>;

// Errors we return via the public interface.
#[derive(Debug, thiserror::Error)]
pub enum TabsApiError {
    #[error("SyncError: {reason}")]
    SyncError { reason: String },

    #[error("SqlError: {reason}")]
    SqlError { reason: String },

    #[error("Unexpected tabs error: {reason}")]
    UnexpectedTabsError { reason: String },
}

// Error we use internally
#[derive(Debug, thiserror::Error)]
pub enum Error {
    // For historical reasons we have a mis-matched name between the error
    // and what the error actually represents.
    #[error("Sync feature is disabled: {0}")]
    SyncAdapterError(String),

    #[error("Error parsing JSON data: {0}")]
    JsonError(#[from] serde_json::Error),

    #[error("Missing SyncUnlockInfo Local ID")]
    MissingLocalIdError,

    #[error("Error parsing URL: {0}")]
    UrlParseError(#[from] url::ParseError),

    #[error("Error executing SQL: {0}")]
    SqlError(#[from] rusqlite::Error),

    #[error("Error opening database: {0}")]
    OpenDatabaseError(#[from] sql_support::open_database::Error),

    #[error("Unexpected connection state")]
    UnexpectedConnectionState,
}

// Define how our internal errors are handled and converted to external errors
// See `support/error/README.md` for how this works, especially the warning about PII.
impl GetErrorHandling for Error {
    type ExternalError = TabsApiError;

    fn get_error_handling(&self) -> ErrorHandling<Self::ExternalError> {
        match self {
            Self::SyncAdapterError(e) => ErrorHandling::convert(TabsApiError::SyncError {
                reason: e.to_string(),
            })
            .report_error("tabs-sync-error"),
            Self::JsonError(e) => ErrorHandling::convert(TabsApiError::UnexpectedTabsError {
                reason: e.to_string(),
            })
            .report_error("tabs-json-error"),
            Self::MissingLocalIdError => {
                ErrorHandling::convert(TabsApiError::UnexpectedTabsError {
                    reason: "MissingLocalId".to_string(),
                })
                .report_error("tabs-missing-local-id-error")
            }
            Self::UrlParseError(e) => ErrorHandling::convert(TabsApiError::UnexpectedTabsError {
                reason: e.to_string(),
            })
            .report_error("tabs-url-parse-error"),
            Self::SqlError(e) => ErrorHandling::convert(TabsApiError::SqlError {
                reason: e.to_string(),
            })
            .report_error("tabs-sql-error"),
            Self::OpenDatabaseError(e) => ErrorHandling::convert(TabsApiError::SqlError {
                reason: e.to_string(),
            })
            .report_error("tabs-open-database-error"),
            Self::UnexpectedConnectionState => {
                ErrorHandling::convert(TabsApiError::UnexpectedTabsError {
                    reason: "Unexpected connection state".to_string(),
                })
                .report_error("tabs-unexpected-connection-state")
            }
        }
    }
}

impl From<anyhow::Error> for TabsApiError {
    fn from(value: anyhow::Error) -> Self {
        TabsApiError::UnexpectedTabsError {
            reason: value.to_string(),
        }
    }
}
